package com.photocompress.app.core.compress

import android.content.Context
import com.photocompress.app.core.jpeg.HeicCompressor
import com.photocompress.app.core.jpeg.JpegCompressor
import com.photocompress.app.core.jpeg.JpegSegments
import com.photocompress.app.core.jpeg.MpfRewriter
import com.photocompress.app.core.livephoto.LivePhotoContainer
import com.photocompress.app.core.mp4.Mp4Metadata
import com.photocompress.app.core.rewrite.FileUtils
import com.photocompress.app.core.rewrite.InPlaceRewriter
import com.photocompress.app.core.rewrite.MediaStoreUpdater
import com.photocompress.app.core.rewrite.RecycleBin
import com.photocompress.app.core.video.MediaClassifierCodec
import com.photocompress.app.core.video.VideoTranscoder
import com.photocompress.app.core.xmp.MarkerStripper
import com.photocompress.app.core.xmp.Mp4XmpMarker
import com.photocompress.app.core.xmp.PcXmp
import com.photocompress.app.data.ledger.CompressedItemEntity
import com.photocompress.app.data.media.ContainerFormat
import com.photocompress.app.data.media.LivePhotoDetector
import com.photocompress.app.data.media.MediaItem
import com.photocompress.app.data.media.MediaKind
import com.photocompress.app.data.media.QualityTier
import com.photocompress.app.data.media.VideoProbeRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.attribute.FileTime

sealed interface CompressOutcome {
    data class Success(val record: CompressedItemEntity) : CompressOutcome
    data class Skipped(val reason: String) : CompressOutcome
    data class Failed(val reason: String) : CompressOutcome
    data class Cancelled(val reason: String? = null) : CompressOutcome
}

sealed interface RestoreOutcome {
    data class Success(val deletedRecordId: String) : RestoreOutcome
    data class Failed(val reason: String) : RestoreOutcome
}

/**
 * 压缩与还原的核心编排（design.md §4）。
 *
 * 所有会改动用户文件的写操作都收敛到 `core.rewrite`；
 * 本类只负责「产出新字节 → 校验 → 备份 → 原地替换 → 同步媒体库 → 失败回滚」。
 */
class CompressionEngine(private val context: Context) {

    private val recycle = RecycleBin(context)

    private class Attempt(
        private val context: Context,
        private val control: CompressionControl,
        private val job: Job?,
        private val onCommit: suspend (CompressedItemEntity) -> Unit,
        private val onRollback: suspend (String) -> Unit,
    ) {
        private val temps = mutableListOf<File>()
        private var publishedId: String? = null
        fun checkCancelled() { control.checkCancelled(); job?.ensureActive() }
        fun temp(suffix: String): File = File.createTempFile("pc_", suffix, context.cacheDir).also { temps += it }
        suspend fun publish(record: CompressedItemEntity) {
            checkCancelled()
            publishedId = record.id
            onCommit(record)
            checkCancelled()
        }
        suspend fun forgetPublished() { publishedId?.let { onRollback(it) }; publishedId = null }
        fun cleanup() { temps.forEach { it.delete() } }
    }

    /**
     * [tier] 决定图像质量档（主图 92/85/76）；
     * [videoTier] 决定视频档位——调用方按媒体类型传入（普通视频取「视频」档，
     * 实况照片内嵌视频取「实况视频段」档），与图像档解耦。
     */
    suspend fun compress(
        item: MediaItem,
        tier: QualityTier,
        videoTier: QualityTier = tier,
        control: CompressionControl = CompressionControl(),
        onCommit: suspend (CompressedItemEntity) -> Unit = {},
        onRollback: suspend (String) -> Unit = {},
    ): CompressOutcome = withContext(Dispatchers.IO) {
        val attempt = Attempt(context, control, currentCoroutineContext()[Job], onCommit, onRollback)
        try {
            attempt.checkCancelled()
            when (item.kind) {
                MediaKind.PHOTO -> if (item.format == ContainerFormat.HEIC) {
                    compressHeic(item, tier, attempt)
                } else {
                    compressPhoto(item, tier, videoTier, attempt)
                }
                MediaKind.LIVE_PHOTO -> compressLivePhoto(item, tier, videoTier, attempt)
                MediaKind.VIDEO -> compressVideo(item, videoTier, attempt)
            }
        } catch (_: CompressionCancelledException) {
            CompressOutcome.Cancelled()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            CompressOutcome.Failed("异常 ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            attempt.cleanup()
        }
    }

    // ---------------------------------------------------------------- HEIC（格式转换）

    /**
     * HEIC/HEIF 压缩：平台无法把元信息写回 HEIF 容器，因此只能**转为 JPEG**。
     * 同目录、同图集，文件名扩展名由 .heic 变为 .jpg；原文件进回收站，还原时按原路径写回。
     */
    private suspend fun compressHeic(item: MediaItem, tier: QualityTier, attempt: Attempt): CompressOutcome {
        val file = File(item.dataPath)
        if (!file.exists()) return CompressOutcome.Failed("文件不存在")
        val originalSize = file.length()
        val mtimeMs = file.lastModified()
        val originalMtime = Files.getLastModifiedTime(file.toPath())
        val originalModifiedSec = (mtimeMs / 1000L).takeIf { it > 0 } ?: item.dateModifiedSec
        val originalDates = MediaStoreUpdater.captureDates(context, item.uri)
        var originalSha = ""
        val id = UUID.randomUUID().toString()
        // HEIC 的编码效率高于 JPEG：用同等质量转 JPEG 往往会变大，
        // 因此这里在同档位上再降一档质量，尽量取得体积收益（仍保留「不变小就跳过」的保护）。
        val quality = (JpegCompressor.qualityFor(tier) - 12).coerceAtLeast(70)

        val temp = attempt.temp(".jpg")
        val convertError = HeicCompressor.convert(file, temp, quality)
        attempt.checkCancelled()
        if (convertError != null) {
            temp.delete()
            return CompressOutcome.Skipped(convertError)
        }
        if (temp.length() >= originalSize) {
            val outSize = temp.length()
            temp.delete()
            return CompressOutcome.Skipped("压缩后体积未减小（$originalSize → $outSize）")
        }
        // 注入自有标记（转换后的 JPEG 通过 ExifInterface 写整包 XMP）
        runCatching {
            val exif = androidx.exifinterface.media.ExifInterface(temp.absolutePath)
            val xmp = runCatching {
                exif.getAttributeBytes(androidx.exifinterface.media.ExifInterface.TAG_XMP)
            }.getOrNull()?.toString(Charsets.UTF_8)
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_XMP,
                PcXmp.injectAttributes(xmp, newMarker()),
            )
            exif.saveAttributes()
        }

        val target = HeicCompressor.uniqueSibling(file, ".jpg")
        var backupRel: String? = null
        var sourceRemoved = false
        var newUri: android.net.Uri? = null
        return try {
            val backup = recycle.backupWithDigest(id, file, attempt::checkCancelled)
            backupRel = backup.relativePath
            originalSha = backup.sha256
            val backupSize = backup.size

            FileUtils.copyWithSha256(temp, target, attempt::checkCancelled, calculateDigest = false)
            check(target.length() == temp.length()) { "转换产物写入长度不一致" }
            restoreMtime(target, originalMtime)
            attempt.checkCancelled()

            // 先物理删除原 HEIC，再重建媒体库索引（删除旧行会连带删文件，顺序不能颠倒）
            check(file.delete()) { "原 HEIC 删除失败" }
            sourceRemoved = true
            newUri = checkNotNull(MediaStoreUpdater.reindex(context, item.uri, target.absolutePath)) { "转换后媒体库索引失败" }
            MediaStoreUpdater.restoreDates(context, newUri, originalDates)
            val newId = android.content.ContentUris.parseId(newUri)
            attempt.checkCancelled()

            val now = System.currentTimeMillis()
            val record = CompressedItemEntity(
                id = id,
                mediaStoreId = newId,
                dataPath = target.absolutePath,
                originalPath = item.dataPath,
                volumeName = item.volumeName,
                bucketName = item.bucketName,
                displayName = target.name,
                mediaKind = MediaKind.PHOTO.name,
                mimeType = "image/jpeg",
                containerFormat = ContainerFormat.JPEG.name,
                videoCodec = null,
                originalSize = originalSize,
                compressedSize = target.length(),
                originalSha256 = originalSha,
                originalDateTakenMs = item.dateTakenMs,
                originalDateAddedSec = item.dateAddedSec,
                originalDateModifiedSec = originalModifiedSec,
                qualityTier = tier.name,
                codecUsed = "HEIC→JPEG q=$quality",
                compressedAtMs = now,
                restoreDeadlineMs = now + TimeUnit.DAYS.toMillis(RETENTION_DAYS),
                backupRelPath = backupRel,
                backupSize = backupSize,
                status = CompressedItemEntity.STATUS_DONE,
            )
            attempt.publish(record)
            CompressOutcome.Success(record)
        } catch (t: Throwable) {
            attempt.cleanup()
            val rollback = withContext(NonCancellable) {
                val fileRollback = runCatching {
                    if (sourceRemoved) {
                        recycle.restore(checkNotNull(backupRel), file, mtimeMs)
                        restoreMtime(file, originalMtime)
                        check(FileUtils.sha256(file) == originalSha) { "HEIC 回滚内容不一致" }
                    }
                    if (target.exists()) check(target.delete()) { "转换临时产物清理失败" }
                    if (sourceRemoved) {
                        val restoredUri = checkNotNull(MediaStoreUpdater.reindex(context, newUri, file.absolutePath)) { "HEIC 索引恢复失败" }
                        MediaStoreUpdater.restoreDates(context, restoredUri, originalDates)
                    }
                }
                if (fileRollback.isSuccess) runCatching { attempt.forgetPublished() } else fileRollback
            }
            if (t is CancellationException && rollback.isSuccess) {
                backupRel?.let { recycle.delete(it) }
                if (t !is CompressionCancelledException) throw t
                CompressOutcome.Cancelled()
            } else if (t is CompressionCancelledException) {
                CompressOutcome.Cancelled("当前 HEIC 回退未通过，原始备份已保留：${rollback.exceptionOrNull()?.message}")
            } else {
                if (t is CancellationException) throw t
                CompressOutcome.Failed("HEIC 转换失败${if (rollback.isFailure) "，回退未通过，备份已保留" else "，原文件已保留或恢复"}：${t.message}")
            }
        } finally {
            temp.delete()
        }
    }

    // ---------------------------------------------------------------- 普通照片

    private suspend fun compressPhoto(item: MediaItem, tier: QualityTier, videoTier: QualityTier, attempt: Attempt): CompressOutcome {
        val file = File(item.dataPath)
        if (!file.exists()) return CompressOutcome.Failed("文件不存在")
        if (item.format != ContainerFormat.JPEG) {
            return CompressOutcome.Skipped("${item.format.label} 本版本不处理")
        }
        val original = file.readBytes()
        attempt.checkCancelled()
        if (!JpegSegments.isJpeg(original)) return CompressOutcome.Skipped("不是有效 JPEG")

        val mpfPayload = JpegSegments.mpfPayloadOf(original)
        if (mpfPayload != null) {
            val n = MpfRewriter.numberOfImages(mpfPayload)
            if (n != 1) return CompressOutcome.Skipped("含${n}图 MPF 多图结构，本版本不处理。")
        }

        val xmp = JpegSegments.xmpTextOf(original)
        val containerItems = LivePhotoDetector.parseContainerItems(xmp ?: "")
        if (containerItems.any { it.semantic == "MotionPhoto" } || containerItems.any { it.semantic == "GainMap" }) {
            // 实况照片 / Ultra HDR 被误判为普通照片时，走容器重组路径
            return compressLivePhoto(item, tier, videoTier, attempt)
        }

        val quality = JpegCompressor.qualityFor(tier)
        val encoded = JpegCompressor.compressJpeg(original, quality)
            ?: return CompressOutcome.Failed("JPEG 解码失败")
        attempt.checkCancelled()

        // 自有标记以属性形式并入原 XMP，尽量不改动文档结构（D5）
        val xmpOut = PcXmp.injectAttributes(xmp, newMarker())

        val pass1 = JpegSegments.rebuildWithMetadata(encoded, original, xmpOut)
        // MPF 的 size 字段是文件总长，需要回填（改写为定长，不影响总长）
        val finalBytes = if (mpfPayload != null) {
            val patched = MpfRewriter.updateEntries(
                mpfPayload, longArrayOf(pass1.size.toLong()), longArrayOf(0L),
            )
            if (patched != null) {
                JpegSegments.rebuildWithMetadata(encoded, original, xmpOut, patched)
            } else {
                pass1
            }
        } else {
            pass1
        }

        if (finalBytes.size >= original.size) {
            return CompressOutcome.Skipped("压缩后体积未减小（${original.size} → ${finalBytes.size}）")
        }
        JpegCompressor.probeSize(finalBytes)
            ?: return CompressOutcome.Failed("输出无法解码")

        val temp = attempt.temp(".jpg")
        temp.writeBytes(finalBytes)
        return commit(item, temp, tier, codecUsed = "JPEG q=${quality}", attempt = attempt)
    }

    // ---------------------------------------------------------------- 实况照片

    private suspend fun compressLivePhoto(
        item: MediaItem,
        tier: QualityTier,
        videoTier: QualityTier,
        attempt: Attempt,
    ): CompressOutcome {
        val file = File(item.dataPath)
        if (!file.exists()) return CompressOutcome.Failed("文件不存在")
        val original = file.readBytes()
        attempt.checkCancelled()
        if (!JpegSegments.isJpeg(original)) return CompressOutcome.Skipped("不是有效 JPEG")

        val xmp = JpegSegments.xmpTextOf(original) ?: return CompressOutcome.Skipped("缺少 XMP，无法解析实况结构")
        val items = LivePhotoDetector.parseContainerItems(xmp)
        val plan = LivePhotoContainer.plan(file, items)
            ?: return CompressOutcome.Skipped("无法解析实况照片容器")

        val mpfPayload = JpegSegments.mpfPayloadOf(original)
        if (mpfPayload != null) {
            val n = MpfRewriter.numberOfImages(mpfPayload)
            if (n != 1 && n != 2) return CompressOutcome.Skipped("含${n}图 MPF 多图结构，本版本不处理。")
        }

        val primaryEnd = plan.primaryEnd.toInt()
        val gainStart = primaryEnd
        val gainEnd = (primaryEnd + plan.gainMapLength).toInt()
        val motionStart = (primaryEnd + plan.gainMapLength + plan.gainMapPadding).toInt()
        val motionEnd = (motionStart + plan.motionLength).toInt()

        val primaryBytes = original.copyOfRange(0, primaryEnd)
        val gainBytes = if (plan.hasGainMap && gainEnd in 1..original.size) original.copyOfRange(gainStart, gainEnd) else null
        val motionBytes = if (plan.hasMotion && motionEnd <= original.size) original.copyOfRange(motionStart, motionEnd) else null

        if (plan.hasMotion && motionBytes == null) return CompressOutcome.Skipped("内嵌视频区段越界")
        if (plan.hasGainMap && gainBytes == null) return CompressOutcome.Skipped("增益图区段越界")

        // 1) 主图重编码（画质维持现有档位，本次改造不动）
        val quality = JpegCompressor.qualityFor(tier)
        val encodedPrimary = JpegCompressor.compressJpeg(primaryBytes, quality)
            ?: return CompressOutcome.Failed("主图解码失败")
        attempt.checkCancelled()

        // 2) 内嵌视频：按 OpCamera:VideoLength 只重编码「主视频」，其后的厂商私有尾块逐字节保留。
        //    无法切分时退回旧逻辑（仅当整个区段本身就是单个普通 MP4 才整段重编码）。
        val split = if (plan.hasMotion && motionBytes != null && plan.motionPadding == 0L) {
            LivePhotoContainer.splitMotion(motionBytes, LivePhotoDetector.extractVideoLength(xmp))
        } else {
            null
        }
        val videoPart: ByteArray? = when {
            split != null -> split.first
            plan.hasMotion && motionBytes != null && LivePhotoContainer.isPlainMp4(motionBytes) -> motionBytes
            else -> null
        }
        val tailPart: ByteArray = split?.second ?: ByteArray(0)

        var newMotionBytes: ByteArray? = null
        var newVideoLength = 0L
        var motionSrc: File? = null
        var motionDst: File? = null
        var motionNote: String = if (!plan.hasMotion) "无内嵌视频" else "内嵌视频原样保留（厂商私有封装）"
        if (videoPart != null) {
            val srcVideo = attempt.temp(".mp4").also { motionSrc = it }
            val dstVideo = attempt.temp(".mp4").also { motionDst = it }
            srcVideo.writeBytes(videoPart)
            val res = VideoTranscoder.transcode(
                srcVideo, dstVideo, videoTier, VideoTranscoder.BitrateProfile.AGGRESSIVE,
                checkCancelled = attempt::checkCancelled,
            )
            if (res.success && dstVideo.exists() && dstVideo.length() < videoPart.size) {
                // 内嵌视频也要保住原有元数据（相机信息等）
                Mp4Metadata.inject(dstVideo, srcVideo)
                val probe = VideoProbeRunner.probe(dstVideo.absolutePath)
                if (probe.codec != null && probe.durationMs > 0) {
                    val videoBytes = dstVideo.readBytes()
                    newVideoLength = videoBytes.size.toLong()
                    // 尾块逐字节原样追加：其索引偏移自区段末尾反向计数，搬移后仍然有效
                    newMotionBytes = videoBytes + tailPart
                    val codecName = res.codec ?: MediaClassifierCodec.label(probe.codec)
                    motionNote = res.hdrNote?.let { "内嵌视频重编码（$codecName · $it）" }
                        ?: "内嵌视频重编码（$codecName）"
                } else {
                    motionNote = "内嵌视频原样保留（重编码结果无法解析）"
                }
            } else {
                // 转码失败或没变小：退回「原样保留」，不影响主图收益
                motionNote = "内嵌视频原样保留（重编码未获益：${res.reason ?: "体积未减小"}）"
            }
        }

        try {
            // 增益图**原样保留**：它是 HDR/ProXDR 重建的依据，重编码会引入偏差（C6 / D10）
            val gainLen = gainBytes?.size?.toLong() ?: 0L
            val motionLen = newMotionBytes?.size?.toLong() ?: (motionBytes?.size?.toLong() ?: 0L)

            // 3) XMP：只在长度真的变化时就地改写数字，并注入自有标记（不改结构）
            val changed = HashMap<String, Long>()
            if (gainBytes != null && gainLen != plan.gainMapLength) changed["GainMap"] = gainLen
            if (newMotionBytes != null && motionLen != plan.motionLength) changed["MotionPhoto"] = motionLen
            var xmpOut = if (changed.isEmpty()) xmp else LivePhotoContainer.rewriteItemLengths(xmp, changed)
            // OpCamera:VideoLength 必须与新主视频字节数一致，否则相册无法定位内嵌视频
            if (newMotionBytes != null) xmpOut = LivePhotoContainer.rewriteVideoLength(xmpOut, newVideoLength)
            xmpOut = PcXmp.injectAttributes(xmpOut, newMarker())

            fun assemblePrimary(mpf: ByteArray?): ByteArray =
                JpegSegments.rebuildWithMetadata(encodedPrimary, primaryBytes, xmpOut, mpf)

            var finalPrimary = assemblePrimary(mpfPayload)
            if (mpfPayload != null && gainBytes != null) {
                // MPEntry 改写为定长，两遍组装后主图长度稳定。
                // offset 必须以 MPF 段的实际位置为基准（相对 MP Endian），
                // 否则相册按 MPF 定位内嵌视频时会整体偏移，导致无法播放。
                val mpfPayloadAt = JpegSegments.mpfPayloadOffset(finalPrimary)
                val patched = mpfPayloadAt?.let {
                    MpfRewriter.updateEntries(
                        mpfPayload,
                        longArrayOf(finalPrimary.size.toLong(), gainLen),
                        longArrayOf(0L, finalPrimary.size.toLong()),
                        offsetBase = it.toLong(),
                    )
                }
                if (patched != null) finalPrimary = assemblePrimary(patched)
            }

            // 4) 拼接：主图 + 增益图 + 内嵌视频
            val out = java.io.ByteArrayOutputStream(
                finalPrimary.size + gainLen.toInt() + motionLen.toInt() + 4096
            )
            out.write(finalPrimary)
            gainBytes?.let { out.write(it) }
            newMotionBytes?.let { out.write(it) } ?: motionBytes?.let { out.write(it) }
            val assembled = out.toByteArray()

            LivePhotoContainer.verify(assembled)?.let {
                return CompressOutcome.Skipped("重组校验失败：$it")
            }
            if (plan.hasMotion) {
                val mp4Start = assembled.size - motionLen.toInt()
                val magic = String(assembled, mp4Start + 4, 4, Charsets.US_ASCII)
                if (magic != "ftyp") return CompressOutcome.Skipped("内嵌视频定位校验失败")
            }
            if (newMotionBytes != null) {
                // 分段自校验：XMP 声明的数字必须与实际重编码后的字节数一致
                val head = String(assembled, 0, minOf(256 * 1024, assembled.size), Charsets.ISO_8859_1)
                if (LivePhotoDetector.extractVideoLength(head) != newVideoLength) {
                    return CompressOutcome.Skipped("实况分段自校验失败（视频长度）")
                }
                val declaredMotion = LivePhotoDetector.parseContainerItems(head)
                    .firstOrNull { it.semantic == "MotionPhoto" }?.length
                if (declaredMotion != null && declaredMotion != motionLen) {
                    return CompressOutcome.Skipped("实况分段自校验失败（区段长度）")
                }
            }
            if (assembled.size >= original.size) {
                return CompressOutcome.Skipped("压缩后体积未减小（${original.size} → ${assembled.size}）")
            }

            attempt.checkCancelled()
            val temp = attempt.temp(".jpg")
            temp.writeBytes(assembled)
            return commit(item, temp, tier, codecUsed = "JPEG q=$quality + 增益图原样 + $motionNote", attempt = attempt)
        } finally {
            motionDst?.delete()
            motionSrc?.delete()
        }
    }

    // ---------------------------------------------------------------- 视频

    private suspend fun compressVideo(item: MediaItem, tier: QualityTier, attempt: Attempt): CompressOutcome {
        val file = File(item.dataPath)
        if (!file.exists()) return CompressOutcome.Failed("文件不存在")
        if (item.format != ContainerFormat.MP4) {
            return CompressOutcome.Skipped("${item.format.label} 容器本版本不处理")
        }
        val dst = attempt.temp(".mp4")
        val res = VideoTranscoder.transcode(file, dst, tier, checkCancelled = attempt::checkCancelled)
        attempt.checkCancelled()
        if (!res.success || !dst.exists()) {
            dst.delete()
            // HDR 源在无法保真时由 VideoTranscoder 返回跳过原因（原文件未改动）
            return CompressOutcome.Skipped(res.reason ?: "转码失败")
        }
        // 双保险：转码跑通但产物丢了 HDR 信号，也不允许落地（原文件保持不变）
        if (res.hdrNote != null && !VideoProbeRunner.probe(dst.absolutePath).isHdr) {
            dst.delete()
            return CompressOutcome.Skipped("输出未保留 HDR 信号，已跳过（原文件未改动）")
        }
        // 写入文件内自有标记（D5），使账本丢失后仍能识别已压缩（F7 / AC5）
        Mp4XmpMarker.write(dst, newMarker())
        // 搬运源文件的 moov 元数据（相机信息 / 拍摄时间），并修正 chunk 偏移
        Mp4Metadata.inject(dst, file)
        if (dst.length() >= file.length()) {
            val outSize = dst.length()
            dst.delete()
            return CompressOutcome.Skipped("压缩后体积未减小（${file.length()} → $outSize）")
        }
        val probe = VideoProbeRunner.probe(dst.absolutePath)
        if (probe.codec == null || probe.durationMs <= 0) {
            dst.delete()
            return CompressOutcome.Failed("输出视频无法解析")
        }
        val codecLabel = MediaClassifierCodec.label(probe.codec)
        // HDR 源的处置结论（保真 / 已转 SDR）随编码信息写入账本，便于识别与追溯
        val codecUsed = res.hdrNote?.let { "$codecLabel · $it" } ?: codecLabel
        return commit(item, dst, tier, codecUsed = codecUsed, attempt = attempt)
    }

    // ---------------------------------------------------------------- 提交（备份 → 原地替换 → 校验 → 回滚）

    private suspend fun commit(
        item: MediaItem,
        tempContent: File,
        tier: QualityTier,
        codecUsed: String?,
        attempt: Attempt,
    ): CompressOutcome {
        val file = File(item.dataPath)
        if (!file.exists()) {
            tempContent.delete()
            return CompressOutcome.Failed("原文件不存在")
        }
        val originalSize = file.length()
        val mtimeMs = file.lastModified()
        val originalMtime = Files.getLastModifiedTime(file.toPath())
        var originalSha = ""
        val id = UUID.randomUUID().toString()
        var backupRel: String? = null
        var writeStarted = false

        return try {
            val backup = recycle.backupWithDigest(id, file, attempt::checkCancelled)
            backupRel = backup.relativePath
            originalSha = backup.sha256
            val backupSize = backup.size

            attempt.checkCancelled()
            writeStarted = true
            InPlaceRewriter.writeFrom(file, tempContent, mtimeMs, attempt::checkCancelled)
            restoreMtime(file, originalMtime)

            // 写回校验
            if (!file.exists() || file.length() != tempContent.length()) {
                throw IllegalStateException("写入后长度不一致")
            }

            MediaStoreUpdater.refresh(
                context, item.uri, item.dataPath,
                item.dateTakenMs, item.dateAddedSec, item.dateModifiedSec,
            )
            attempt.checkCancelled()

            val now = System.currentTimeMillis()
            val record = CompressedItemEntity(
                id = id,
                mediaStoreId = item.id,
                dataPath = item.dataPath,
                originalPath = item.dataPath,
                volumeName = item.volumeName,
                bucketName = item.bucketName,
                displayName = item.displayName,
                mediaKind = item.kind.name,
                mimeType = item.mimeType,
                containerFormat = item.format.name,
                videoCodec = item.videoCodec,
                originalSize = originalSize,
                compressedSize = tempContent.length(),
                originalSha256 = originalSha,
                originalDateTakenMs = item.dateTakenMs,
                originalDateAddedSec = item.dateAddedSec,
                originalDateModifiedSec = item.dateModifiedSec,
                qualityTier = tier.name,
                codecUsed = codecUsed,
                compressedAtMs = now,
                restoreDeadlineMs = now + TimeUnit.DAYS.toMillis(RETENTION_DAYS),
                backupRelPath = backupRel,
                backupSize = backupSize,
                status = CompressedItemEntity.STATUS_DONE,
            )
            attempt.publish(record)
            CompressOutcome.Success(record)
        } catch (t: Throwable) {
            // 刷新未通过同样不能记为成功。保留备份，并分别报告文件恢复与相册同步结果。
            attempt.cleanup()
            var bytesRestored = false
            val rollback = withContext(NonCancellable) {
                val fileRollback = backupRel?.takeIf { writeStarted }?.let { backupPath -> runCatching {
                    recycle.restore(backupPath, file, mtimeMs)
                    restoreMtime(file, originalMtime)
                    check(FileUtils.sha256(file) == originalSha) { "回滚内容与原文件不一致" }
                    bytesRestored = true
                    MediaStoreUpdater.refresh(
                        context, item.uri, item.dataPath,
                        item.dateTakenMs, item.dateAddedSec, item.dateModifiedSec,
                    )
                } } ?: Result.success(Unit)
                if (fileRollback.isSuccess) runCatching { attempt.forgetPublished() } else fileRollback
            }
            val state = when {
                !writeStarted -> "原文件未改动"
                rollback.isSuccess -> "写入失败，原文件及相册记录已恢复"
                bytesRestored -> "原文件已恢复，但相册或账本同步未通过；原始备份已保留"
                else -> "回滚未通过；原始备份已保留，请勿清理备份"
            }
            if (t is CancellationException && rollback.isSuccess) {
                backupRel?.let { recycle.delete(it) }
                if (t !is CompressionCancelledException) throw t
                CompressOutcome.Cancelled()
            } else if (t is CompressionCancelledException) {
                CompressOutcome.Cancelled("$state：${rollback.exceptionOrNull()?.message}")
            } else {
                if (t is CancellationException) throw t
                CompressOutcome.Failed("$state：${t.javaClass.simpleName}: ${t.message}")
            }
        } finally {
            tempContent.delete()
        }
    }

    // ---------------------------------------------------------------- 还原

    suspend fun restore(record: CompressedItemEntity): RestoreOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val backupRel = record.backupRelPath
                ?: return@runCatching RestoreOutcome.Failed("备份已清理，无法还原")
            val backup = recycle.fileOf(backupRel)
            if (!backup.exists()) return@runCatching RestoreOutcome.Failed("备份文件缺失，无法还原")

            // 格式转换（HEIC→JPEG）时需写回原路径，并删除转换产物
            val originalPath = record.originalPath.ifBlank { record.dataPath }
            val target = File(originalPath)
            target.parentFile?.mkdirs()
            val mtimeMs = record.originalDateModifiedSec * 1000L

            InPlaceRewriter.writeFrom(target, backup, mtimeMs)

            // 以备份自身为校验基准：备份就是压缩前的原文件
            val backupSha = FileUtils.sha256(backup)
            val restoredSha = FileUtils.sha256(target)
            if (backupSha != restoredSha) {
                return@runCatching RestoreOutcome.Failed("还原校验失败，文件与备份不一致")
            }
            // 防御：历史版本可能把自有标记写进了备份，还原后必须清掉，
            // 否则该照片会被误判为「已压缩」而回不到未压缩页
            MarkerStripper.strip(target)

            val convertedPath = record.dataPath.takeIf { it != originalPath }
            if (convertedPath != null) {
                // 格式转换（HEIC→JPEG）：删除转换产物与它的媒体库行，再为新路径建索引。
                // 注意：MediaProvider 删除行时会一并删除磁盘文件，因此这里必须先物理删除，
                // 且**不能**对原地还原的路径调用 reindex（会误删刚还原的文件）。
                runCatching { File(convertedPath).delete() }
                MediaStoreUpdater.reindex(context, mediaUriOf(record), originalPath)
            } else {
                // 原地还原：媒体库行仍然有效，只需同步大小与时间字段
                MediaStoreUpdater.refresh(
                    context, mediaUriOf(record), originalPath,
                    record.originalDateTakenMs, record.originalDateAddedSec, record.originalDateModifiedSec,
                )
            }
            recycle.delete(backupRel)
            RestoreOutcome.Success(record.id)
        }.getOrElse { t ->
            RestoreOutcome.Failed("异常 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 清理备份：只删备份，已压缩文件保持不动（E 需求：清理后仍显示为已压缩）。 */
    suspend fun purgeBackups(
        records: List<CompressedItemEntity>,
        onPurged: suspend (List<String>) -> Unit,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        clearUntracked: Boolean = false,
    ): RecycleBin.PurgeResult = withContext(Dispatchers.IO) {
        var freed = 0L
        var failed = 0
        val pending = ArrayList<String>(64)
        val protectedPaths = mutableSetOf<String>()
        suspend fun flush() {
            if (pending.isEmpty()) return
            withContext(NonCancellable) { onPurged(pending.toList()) }
            pending.clear()
        }
        try {
            records.forEachIndexed { index, record ->
                currentCoroutineContext().ensureActive()
                val rel = record.backupRelPath
                if (rel != null) {
                    try {
                        freed += recycle.deleteBackupChecked(rel)
                        pending += record.id
                    } catch (_: Exception) {
                        failed++
                        protectedPaths += File(rel).normalize().path.replace(File.separatorChar, '/')
                    }
                }
                if (pending.size >= 64) flush()
                if ((index + 1) % 64 == 0 || index == records.lastIndex) {
                    onProgress(index + 1, records.size)
                }
            }
        } finally {
            // 生命周期取消也必须登记本轮已删除的备份，不能把它们继续标成可还原。
            flush()
        }
        if (clearUntracked) {
            val residual = recycle.purgeUntracked(protectedPaths)
            freed += residual.freedBytes
            failed += residual.failedCount
        }
        RecycleBin.PurgeResult(freed, failed)
    }

    fun recycleBinSize(): Long = recycle.totalSize()

    /** 当前事务直接保留系统提供的时间精度，不在取消回退时截断为秒或毫秒。 */
    private fun restoreMtime(file: File, time: FileTime) {
        Files.setLastModifiedTime(file.toPath(), time)
        check(Files.getLastModifiedTime(file.toPath()) == time) { "文件修改时间恢复未通过" }
    }

    private fun mediaUriOf(record: CompressedItemEntity): android.net.Uri {
        val base = if (record.mediaKind == MediaKind.VIDEO.name) {
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return android.content.ContentUris.withAppendedId(base, record.mediaStoreId)
    }

    private fun newMarker() = PcXmp.Marker(
        id = UUID.randomUUID().toString(),
        version = APP_VERSION,
        timeMs = System.currentTimeMillis(),
    )

    companion object {
        const val APP_VERSION = "0.1.0"
        const val RETENTION_DAYS = 30L
    }
}
