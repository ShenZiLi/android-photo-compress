package com.photocompress.app.core.compress

import android.content.Context
import com.photocompress.app.core.jpeg.JpegCompressor
import com.photocompress.app.core.jpeg.JpegSegments
import com.photocompress.app.core.jpeg.MpfRewriter
import com.photocompress.app.core.jpeg.MpfPhotoContainer
import com.photocompress.app.core.png.PngCompressor
import com.photocompress.app.core.livephoto.LivePhotoContainer
import com.photocompress.app.core.mp4.Mp4Metadata
import com.photocompress.app.core.rewrite.FileUtils
import com.photocompress.app.core.rewrite.InPlaceRewriter
import com.photocompress.app.core.rewrite.MediaStoreUpdater
import com.photocompress.app.core.rewrite.RecycleBin
import com.photocompress.app.core.rewrite.RecoveryJournal
import com.photocompress.app.core.rewrite.PngConversionRewriter
import com.photocompress.app.core.video.MediaClassifierCodec
import com.photocompress.app.core.video.VideoTranscoder
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.attribute.FileTime

sealed interface CompressOutcome {
    data class Success(val record: CompressedItemEntity) : CompressOutcome
    data class Skipped(val reason: String, val noSizeReduction: Boolean = false) : CompressOutcome {
        companion object {
            fun noSizeReduction() = Skipped("压缩后体积未减小", noSizeReduction = true)
        }
    }
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
    private val recovery = RecoveryJournal(context)
    private val pngConversion = PngConversionRewriter(context)

    /** UI 重试与到期清理共享锁，避免检查保护记录后另一事务开始改写或删除备份。 */
    private suspend fun <T> withMediaLock(
        dispatcher: CoroutineContext = Dispatchers.IO,
        block: suspend () -> T,
    ): T = withContext(dispatcher) { mediaLock.withLock { block() } }

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
        /** 原片未改写；登记完成即为已处理项，取消只阻止尚未开始的下一项。 */
        suspend fun publishUnchanged(record: CompressedItemEntity) {
            checkCancelled()
            withContext(NonCancellable) { onCommit(record) }
        }
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
        pngEnabled: Boolean = false,
    ): CompressOutcome = withMediaLock {
        val attempt = Attempt(context, control, currentCoroutineContext()[Job], onCommit, onRollback)
        try {
            attempt.checkCancelled()
            check(recovery.entries().none { it.path == item.dataPath || it.convertedPath == item.dataPath || it.mediaUri == item.uri.toString() }) {
                "处理未完成，请在未压缩页重试恢复原片"
            }
            val source = File(item.dataPath)
            val originalSize = source.length()
            val originalModified = source.lastModified()
            val outcome = when (item.kind) {
                MediaKind.PHOTO -> when (item.format) {
                    ContainerFormat.HEIC -> CompressOutcome.Skipped("HEIC 原格式及元数据无法完整保留，已保留原片")
                    ContainerFormat.PNG -> if (pngEnabled) compressPng(item, tier, attempt) else CompressOutcome.Skipped("PNG 压缩未开启")
                    else -> compressPhoto(item, tier, videoTier, attempt)
                }
                MediaKind.LIVE_PHOTO -> compressLivePhoto(item, tier, videoTier, attempt)
                MediaKind.VIDEO -> compressVideo(item, videoTier, attempt)
            }
            if (outcome is CompressOutcome.Skipped && outcome.noSizeReduction && item.kind != MediaKind.VIDEO) {
                attempt.publishUnchanged(skippedRecord(item, tier, originalSize, originalModified))
            }
            outcome
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

    // ---------------------------------------------------------------- 普通照片

    private suspend fun compressPng(item: MediaItem, tier: QualityTier, attempt: Attempt): CompressOutcome {
        val file = File(item.dataPath)
        if (!file.isFile) return CompressOutcome.Failed("文件不存在")
        if (file.length() > PngCompressor.MAX_FILE_BYTES) return CompressOutcome.Skipped("PNG 文件过大，已保留原片")
        val original = file.readBytes()
        attempt.checkCancelled()
        val marker = newMarker()
        val encoded = try {
            PngCompressor.compress(original, tier, marker, attempt::checkCancelled)
        } catch (invalid: IllegalArgumentException) {
            return CompressOutcome.Skipped(invalid.message ?: "PNG 无法安全压缩，已保留原片")
        }
        if (encoded.bytes.size >= original.size) return CompressOutcome.Skipped.noSizeReduction()
        val destination = File(file.parentFile, file.nameWithoutExtension + ".jpg")
        if (destination.exists()) return CompressOutcome.Skipped("同名 JPEG 已存在，已保留 PNG 原片")
        val temp = attempt.temp(".jpg")
        temp.writeBytes(encoded.bytes)
        val sourceSha = java.security.MessageDigest.getInstance("SHA-256").digest(original)
            .joinToString("") { "%02x".format(it) }
        return commitPngJpeg(item, temp, tier, encoded.quality, destination, sourceSha, attempt)
    }

    private suspend fun commitPngJpeg(item: MediaItem, temp: File, tier: QualityTier, quality: Int,
        destination: File, sourceSha: String, attempt: Attempt): CompressOutcome {
        var prepared: PngConversionRewriter.Prepared? = null
        var started = false
        try {
            attempt.checkCancelled()
            prepared = pngConversion.prepare(UUID.randomUUID().toString(), item, destination, sourceSha, attempt::checkCancelled)
            attempt.checkCancelled()
            started = true
            pngConversion.writeJpeg(prepared.entry, temp, attempt::checkCancelled)
            MediaStoreUpdater.refresh(context, item.uri, destination.path, item.dateTakenMs, item.dateAddedSec, item.dateModifiedSec)
            attempt.checkCancelled()
            val now = System.currentTimeMillis()
            val record = CompressedItemEntity(
                id = prepared.entry.id, mediaStoreId = item.id, dataPath = destination.path, originalPath = item.dataPath,
                volumeName = item.volumeName, bucketName = item.bucketName, displayName = destination.name,
                mediaKind = MediaKind.PHOTO.name, mimeType = "image/jpeg", containerFormat = ContainerFormat.JPEG.name,
                videoCodec = null, originalSize = prepared.size, compressedSize = temp.length(), originalSha256 = sourceSha,
                originalDateTakenMs = item.dateTakenMs, originalDateAddedSec = item.dateAddedSec, originalDateModifiedSec = item.dateModifiedSec,
                qualityTier = tier.name, codecUsed = "PNG → JPEG q=$quality", compressedAtMs = now,
                restoreDeadlineMs = now + TimeUnit.DAYS.toMillis(RETENTION_DAYS), backupRelPath = prepared.entry.backupRelPath,
                backupSize = prepared.size, status = CompressedItemEntity.STATUS_DONE,
            )
            attempt.publish(record)
            recovery.finish(prepared.entry.id)
            return CompressOutcome.Success(record)
        } catch (failure: Throwable) {
            val rollback = withContext(NonCancellable) {
                val unregister = runCatching { attempt.forgetPublished() }
                val restored = runCatching { prepared?.let { pngConversion.restoreOriginal(it.entry) } }
                if (unregister.isSuccess && restored.isSuccess) runCatching { prepared?.let { pngConversion.cleanup(it.entry) } }
                else if (restored.isFailure) restored else unregister
            }
            if (failure is CancellationException && rollback.isSuccess) {
                if (failure !is CompressionCancelledException) throw failure
                return CompressOutcome.Cancelled()
            }
            val state = if (rollback.isSuccess) {
                if (started) "转换失败，PNG 原片及相册记录已恢复" else "PNG 原片未改动"
            } else "PNG 转换未完成，原始备份已保护，可在未压缩页重试恢复"
            if (failure is CancellationException && failure !is CompressionCancelledException) throw failure
            return if (failure is CompressionCancelledException) CompressOutcome.Cancelled("$state：${rollback.exceptionOrNull()?.message}")
                else CompressOutcome.Failed("$state：${failure.message}")
        }
    }

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
        val xmp = JpegSegments.xmpTextOf(original)
        val containerItems = LivePhotoDetector.parseContainerItems(xmp ?: "")
        if (mpfPayload != null) {
            val n = MpfRewriter.numberOfImages(mpfPayload)
            if (n >= 2 && (n > 2 || containerItems.none { it.semantic == "MotionPhoto" })) {
                return compressMpfPhoto(item, original, xmp, tier, attempt)
            }
            if (n != 1) return CompressOutcome.Skipped("含${n}图 MPF 多图结构，本版本不处理。")
        }

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
            return CompressOutcome.Skipped.noSizeReduction()
        }
        JpegCompressor.probeSize(finalBytes)
            ?: return CompressOutcome.Failed("输出无法解码")

        val temp = attempt.temp(".jpg")
        temp.writeBytes(finalBytes)
        return commit(item, temp, tier, codecUsed = "JPEG q=${quality}", attempt = attempt)
    }

    private suspend fun compressMpfPhoto(
        item: MediaItem, original: ByteArray, xmp: String?, tier: QualityTier, attempt: Attempt,
    ): CompressOutcome {
        val plan = MpfPhotoContainer.plan(original)
            ?: return CompressOutcome.Skipped("MPF 图片边界或偏移无效，已保留原片")
        val primary = original.copyOfRange(0, plan.primaryEnd)
        val originalDimensions = JpegCompressor.probeSize(primary)
            ?: return CompressOutcome.Skipped("MPF 主图无法解码，已保留原片")
        val quality = JpegCompressor.qualityFor(tier)
        // 只解码主图，避免平台解码整个 HDR 容器后将增益映射结果烘焙进 SDR 基图。
        val encoded = JpegCompressor.compressJpeg(primary, quality)
            ?: return CompressOutcome.Skipped("MPF 主图无法重编码，已保留原片")
        attempt.checkCancelled()
        val xmpOut = PcXmp.injectAttributes(xmp, newMarker())
        if (xmp != null && PcXmp.removeOwn(xmpOut) != PcXmp.removeOwn(xmp)) {
            return CompressOutcome.Skipped("MPF 的 XMP 无法完整保留，已保留原片")
        }
        val out = MpfPhotoContainer.rebuild(original, plan, encoded, xmpOut)
            ?: return CompressOutcome.Skipped("MPF 重组校验未通过，已保留原片")
        if (JpegCompressor.probeSize(out) != originalDimensions) {
            return CompressOutcome.Skipped("MPF 输出尺寸校验未通过，已保留原片")
        }
        if (out.size >= original.size) return CompressOutcome.Skipped.noSizeReduction()
        attempt.checkCancelled()
        val temp = attempt.temp(".jpg")
        temp.writeBytes(out)
        val retained = if (LivePhotoDetector.parseContainerItems(xmp ?: "").any { it.semantic == "MotionPhoto" }) {
            "附加图、视频与尾部原样"
        } else "附加图与尾部原样"
        return commit(item, temp, tier, codecUsed = "MPF ${plan.imageCount}图 JPEG q=$quality + $retained", attempt = attempt)
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
        val mpfPayload = JpegSegments.mpfPayloadOf(original)
        if (mpfPayload != null) {
            val n = MpfRewriter.numberOfImages(mpfPayload)
            // 已编辑实况的 Original 可嵌套 MPF；保留完整关联后缀，不套用三段实况重组器。
            if (n > 2) return compressMpfPhoto(item, original, xmp, tier, attempt)
            if (n != 1 && n != 2) return CompressOutcome.Skipped("含${n}图 MPF 多图结构，本版本不处理。")
        }

        val items = LivePhotoDetector.parseContainerItems(xmp)
        val plan = LivePhotoContainer.plan(file, items)
            ?: return CompressOutcome.Skipped("无法解析实况照片容器")

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
                return CompressOutcome.Skipped.noSizeReduction()
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
            dst.delete()
            return CompressOutcome.Skipped.noSizeReduction()
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

    /** 无收益仅记录原片身份，不写 XMP、不创建备份、不修改媒体库。 */
    private fun skippedRecord(item: MediaItem, tier: QualityTier, size: Long, modified: Long): CompressedItemEntity {
        val file = File(item.dataPath)
        fun verifyUnchanged() = check(file.isFile && size == item.size && file.length() == size &&
            file.lastModified() == modified) { "处理期间原片已变化，未登记跳过，请重新扫描" }
        verifyUnchanged()
        val sha256 = FileUtils.sha256(file)
        verifyUnchanged()
        return CompressedItemEntity(
            id = UUID.nameUUIDFromBytes("skip:${item.volumeName}:${item.id}:${item.dataPath}".toByteArray(Charsets.UTF_8)).toString(),
            mediaStoreId = item.id,
            dataPath = item.dataPath,
            volumeName = item.volumeName,
            bucketName = item.bucketName,
            displayName = item.displayName,
            mediaKind = item.kind.name,
            mimeType = item.mimeType,
            containerFormat = item.format.name,
            videoCodec = item.videoCodec,
            originalSize = size,
            compressedSize = size,
            originalSha256 = sha256,
            originalDateTakenMs = item.dateTakenMs,
            originalDateAddedSec = item.dateAddedSec,
            originalDateModifiedSec = item.dateModifiedSec,
            qualityTier = tier.name,
            codecUsed = null,
            compressedAtMs = System.currentTimeMillis(),
            restoreDeadlineMs = 0L,
            backupRelPath = null,
            backupSize = 0L,
            status = CompressedItemEntity.STATUS_SKIPPED,
            failureReason = CompressOutcome.Skipped.noSizeReduction().reason,
        )
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
        var capturedDates: android.content.ContentValues? = null

        return try {
            val originalDates = MediaStoreUpdater.captureDates(context, item.uri)
            capturedDates = originalDates
            val backup = recycle.backupWithDigest(id, file, attempt::checkCancelled)
            backupRel = backup.relativePath
            originalSha = backup.sha256
            val backupSize = backup.size

            recovery.begin(RecoveryJournal.Entry(
                id, item.dataPath, backup.relativePath, backup.sha256, originalMtime.toString(),
                item.uri.toString(), originalDates.getAsLong(android.provider.MediaStore.Images.Media.DATE_TAKEN) ?: 0L,
                originalDates.getAsLong(android.provider.MediaStore.MediaColumns.DATE_ADDED) ?: item.dateAddedSec,
                originalDates.getAsLong(android.provider.MediaStore.MediaColumns.DATE_MODIFIED) ?: item.dateModifiedSec,
                dateTakenWasNull = originalDates.getAsLong(android.provider.MediaStore.Images.Media.DATE_TAKEN) == null,
            ))
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
            recovery.finish(id)
            CompressOutcome.Success(record)
        } catch (t: Throwable) {
            // 刷新未通过同样不能记为成功。保留备份，并分别报告文件恢复与相册同步结果。
            attempt.cleanup()
            var bytesRestored = false
            val rollback = withContext(NonCancellable) {
                // 撤销登记不能依赖相册刷新成功；数据库失败也不能阻止原片字节回滚。
                val unregister = runCatching { attempt.forgetPublished() }
                val fileRollback = backupRel?.takeIf { writeStarted }?.let { backupPath -> runCatching {
                    check(FileUtils.sha256(recycle.fileOf(backupPath)) == originalSha) { "回滚备份校验失败，未覆盖当前照片" }
                    recycle.restore(backupPath, file, 0)
                    check(FileUtils.sha256(file) == originalSha) { "回滚内容与原文件不一致" }
                    bytesRestored = true
                    restoreMtime(file, originalMtime)
                    capturedDates?.let { MediaStoreUpdater.restoreDates(context, item.uri, it) }
                    MediaStoreUpdater.refresh(
                        context, item.uri, item.dataPath,
                        item.dateTakenMs, item.dateAddedSec, item.dateModifiedSec,
                    )
                } } ?: Result.success(Unit)
                if (fileRollback.isSuccess && unregister.isSuccess) runCatching {
                    backupRel?.let { path ->
                        check(file.isFile && FileUtils.sha256(file) == originalSha) { "原片校验未通过，备份继续保护" }
                        recycle.deleteBackupChecked(path)
                    }
                    recovery.finish(id)
                } else if (fileRollback.isFailure) fileRollback else unregister
            }
            val state = when {
                !writeStarted -> "原文件未改动"
                rollback.isSuccess -> "写入失败，原文件及相册记录已恢复"
                bytesRestored -> "原文件已恢复，但相册或账本同步未通过；原始备份已保留"
                else -> "回滚未通过；原始备份已保留，请勿清理备份"
            }
            if (t is CancellationException && rollback.isSuccess) {
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

    suspend fun restore(
        record: CompressedItemEntity,
        onRestored: suspend (String) -> Unit = {},
    ): RestoreOutcome = withMediaLock {
        val rel = record.backupRelPath
            ?: return@withMediaLock RestoreOutcome.Failed("备份已清理，无法还原")
        val backup = recycle.fileOf(rel)
        if (!backup.isFile) return@withMediaLock RestoreOutcome.Failed("备份文件缺失，原片未改动")
        val originalPath = record.originalPath.ifBlank { record.dataPath }
        val target = File(originalPath)
        val converted = originalPath != record.dataPath
        if (converted && File(originalPath).extension.equals("png", ignoreCase = true)) {
            return@withMediaLock restorePngJpeg(record, originalPath, rel, onRestored)
        }
        var safety: RecycleBin.Backup? = null
        val previousTime = target.takeIf { it.isFile }?.let { Files.getLastModifiedTime(it.toPath()) }
        val restoreTime = previousTime?.takeIf { it.toMillis() / 1000L == record.originalDateModifiedSec }
            ?: java.nio.file.attribute.FileTime.fromMillis(record.originalDateModifiedSec * 1000L)
        var wrote = false
        var originalRestored = false
        val journalId = "RESTORE-${UUID.randomUUID()}"
        try {
            val originalSha = FileUtils.sha256(backup)
            check(record.originalSha256.isBlank() || originalSha == record.originalSha256) {
                "备份校验失败，原片未改动"
            }
            if (converted && target.exists()) {
                check(target.isFile && FileUtils.sha256(target) == originalSha) {
                    "原路径已存在其他文件，未覆盖"
                }
            }
            // 还原写入失败时，仍能回退到操作前的可用压缩图片/视频。
            if (target.isFile) safety = recycle.backupWithDigest("SAFE-${UUID.randomUUID()}", target)
            val entry = RecoveryJournal.Entry(
                journalId, originalPath, rel, originalSha,
                restoreTime.toString(),
                mediaUriOf(record).toString(), record.originalDateTakenMs, record.originalDateAddedSec,
                record.originalDateModifiedSec, safety?.relativePath, record.id,
            )
            recovery.begin(entry)
            withContext(NonCancellable) {
                wrote = true
                target.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法恢复原目录" } }
                recycle.restore(rel, target, 0)
                check(FileUtils.sha256(target) == originalSha) { "还原校验失败" }
                originalRestored = true
                restoreMtime(target, java.nio.file.attribute.FileTime.from(java.time.Instant.parse(entry.modifiedTime)))
                if (converted || !MediaStoreUpdater.pointsTo(context, mediaUriOf(record), originalPath)) {
                    val uri = MediaStoreUpdater.scanExisting(context, originalPath)
                    MediaStoreUpdater.restoreDates(context, uri, android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DATE_TAKEN, record.originalDateTakenMs)
                        put(android.provider.MediaStore.MediaColumns.DATE_ADDED, record.originalDateAddedSec)
                        put(android.provider.MediaStore.MediaColumns.DATE_MODIFIED, record.originalDateModifiedSec)
                    })
                } else {
                    MediaStoreUpdater.refresh(context, mediaUriOf(record), originalPath,
                        record.originalDateTakenMs, record.originalDateAddedSec, record.originalDateModifiedSec)
                }
                // 先登记还原结果；失败时两份文件和恢复入口都留下。
                onRestored(record.id)
                recovery.finish(journalId)
                // 历史 HEIC 转换产物保留，避免旧 URI 别名或时间恢复失败造成再次丢图。
                recycle.delete(rel)
                safety?.let { recycle.delete(it.relativePath) }
            }
            RestoreOutcome.Success(record.id)
        } catch (failure: Throwable) {
            val rollback = withContext(NonCancellable) {
                runCatching {
                    if (wrote && !originalRestored && safety != null) {
                        recycle.restore(checkNotNull(safety).relativePath, target, 0)
                        check(FileUtils.sha256(target) == checkNotNull(safety).sha256) { "还原失败后的回退校验失败" }
                        previousTime?.let { restoreMtime(target, it) }
                    }
                }
            }
            if (!wrote) {
                runCatching { recovery.finish(journalId) }
                safety?.let { recycle.delete(it.relativePath) }
            }
            if (failure is CancellationException) throw failure
            val state = when {
                !wrote -> "原片未改动"
                originalRestored -> "原片已还原，日期或相册同步未通过；可在未压缩页重试恢复"
                rollback.isSuccess && safety != null -> "还原失败，操作前的照片已恢复；原始备份已保护"
                else -> "还原未完成；原始备份已保护，可在未压缩页重试恢复"
            }
            RestoreOutcome.Failed("$state：${failure.message}")
        }
    }

    fun pendingRecovery(): List<RecoveryJournal.Entry> = recovery.entries()

    private suspend fun restorePngJpeg(record: CompressedItemEntity, originalPath: String, backup: String,
        onRestored: suspend (String) -> Unit): RestoreOutcome = withContext(NonCancellable) {
        var entry: RecoveryJournal.Entry? = null
        try {
            entry = pngConversion.prepareRestore("RESTORE-${UUID.randomUUID()}", originalPath, record.dataPath,
                backup, record.originalSha256, record.id, mediaUriOf(record))
            pngConversion.restoreOriginal(entry)
            onRestored(record.id)
            pngConversion.cleanup(entry)
            RestoreOutcome.Success(record.id)
        } catch (failure: Exception) {
            RestoreOutcome.Failed(if (entry == null) "PNG 原片未改动：${failure.message}"
                else "PNG 还原未完成，原始备份已保护，可在未压缩页重试恢复：${failure.message}")
        }
    }

    private suspend fun recoverPngEntry(entry: RecoveryJournal.Entry, onRecovered: suspend (String) -> Unit): RestoreOutcome {
        return try {
            val known = recovery.entries().firstOrNull { it.id == entry.id }
                ?: return RestoreOutcome.Failed("恢复记录已处理，请刷新")
            pngConversion.restoreOriginal(known)
            onRecovered(known.ledgerId)
            pngConversion.cleanup(known)
            RestoreOutcome.Success(known.id)
        } catch (failure: Exception) {
            RestoreOutcome.Failed("PNG 恢复未完成，原始备份继续保护：${failure.message}")
        }
    }

    /** 自动重试持久事务或从未压缩页重试；源文件、时间及账本完整后才清理本次备份。 */
    suspend fun recover(entry: RecoveryJournal.Entry, onRecovered: suspend (String) -> Unit): RestoreOutcome =
        withMediaLock(Dispatchers.IO + NonCancellable) {
            if (entry.convertedPath != null) return@withMediaLock recoverPngEntry(entry, onRecovered)
            var bytesRestored = false
            var safety: RecycleBin.Backup? = null
            var previousTime: FileTime? = null
            var target: File? = null
            var wrote = false
            try {
                val known = recovery.entries().firstOrNull { it.id == entry.id }
                    ?: return@withMediaLock RestoreOutcome.Failed("恢复记录已处理，请刷新")
                val file = File(known.path)
                target = file
                val alreadyOriginal = file.isFile && FileUtils.sha256(file) == known.sha256
                val backup = recycle.fileOf(known.backupRelPath)
                if (!alreadyOriginal) {
                    check(backup.isFile && FileUtils.sha256(backup) == known.sha256) { "原始备份校验失败，未覆盖照片" }
                }
                file.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法恢复原目录，备份继续保留" } }
                if (file.isFile && !alreadyOriginal) {
                    previousTime = Files.getLastModifiedTime(file.toPath())
                    safety = recycle.backupWithDigest("SAFE-${UUID.randomUUID()}", file)
                    recovery.begin(known.copy(safetyBackupRelPath = checkNotNull(safety).relativePath))
                    wrote = true
                    recycle.restore(known.backupRelPath, file, 0)
                } else if (!file.exists()) {
                    wrote = true
                    recycle.restore(known.backupRelPath, file, 0)
                }
                check(FileUtils.sha256(file) == known.sha256) { "原片恢复校验失败" }
                bytesRestored = true
                restoreMtime(file, java.nio.file.attribute.FileTime.from(java.time.Instant.parse(known.modifiedTime)))
                val oldUri = android.net.Uri.parse(known.mediaUri)
                val dates = android.content.ContentValues().apply {
                    if (known.dateTakenWasNull) putNull(android.provider.MediaStore.Images.Media.DATE_TAKEN)
                    else put(android.provider.MediaStore.Images.Media.DATE_TAKEN, known.dateTakenMs)
                    put(android.provider.MediaStore.MediaColumns.DATE_ADDED, known.dateAddedSec)
                    put(android.provider.MediaStore.MediaColumns.DATE_MODIFIED, known.dateModifiedSec)
                }
                if (MediaStoreUpdater.pointsTo(context, oldUri, known.path)) {
                    MediaStoreUpdater.restoreDates(context, oldUri, dates)
                    MediaStoreUpdater.refresh(context, oldUri, known.path, known.dateTakenMs,
                        known.dateAddedSec, known.dateModifiedSec)
                } else {
                    val uri = MediaStoreUpdater.scanExisting(context, known.path)
                    MediaStoreUpdater.restoreDates(context, uri, dates)
                }
                onRecovered(known.ledgerId)
                // 缺失备份但原片已完整恢复时允许收尾；未知/损坏副本仍不删除。
                if (backup.isFile && FileUtils.sha256(backup) == known.sha256) recycle.deleteBackupChecked(known.backupRelPath)
                listOfNotNull(known.safetyBackupRelPath, safety?.relativePath).distinct()
                    .filter { it != known.backupRelPath }.forEach(recycle::deleteBackupChecked)
                recovery.finish(known.id)
                RestoreOutcome.Success(known.id)
            } catch (failure: Exception) {
                if (wrote && !bytesRestored && safety != null && target != null) {
                    runCatching {
                        recycle.restore(checkNotNull(safety).relativePath, checkNotNull(target), 0)
                        check(FileUtils.sha256(checkNotNull(target)) == checkNotNull(safety).sha256)
                        previousTime?.let { restoreMtime(checkNotNull(target), it) }
                    }
                }
                RestoreOutcome.Failed(if (bytesRestored) "原片已恢复，相册或日期同步未通过；备份继续保护：${failure.message}"
                    else "恢复未完成，原始备份继续保护：${failure.message}")
            }
        }

    /** 旧版假 DONE：只有源文件与登记原片摘要一致，才修正状态；不改动原片。 */
    suspend fun reconcileRolledBack(
        records: List<CompressedItemEntity>,
        onFailed: suspend (String) -> Unit,
        onRemoved: suspend (String) -> Unit,
    ): List<String> = withMediaLock {
        val pending = recovery.entries().flatMap { it.paths }.toSet()
        val touched = mutableListOf<String>()
        for (record in records) {
            if (record.status !in setOf(CompressedItemEntity.STATUS_DONE, CompressedItemEntity.STATUS_FAILED) ||
                record.backupRelPath == null || record.originalSize == record.compressedSize ||
                record.originalSha256.isBlank() || record.dataPath in pending ||
                record.originalPath.isNotBlank() && record.originalPath != record.dataPath) continue
            val file = File(record.dataPath)
            if (!file.isFile || file.length() != record.originalSize || FileUtils.sha256(file) != record.originalSha256) continue
            onFailed(record.id)
            touched += record.dataPath
            // 日期、条目或备份证据缺失时保留 FAILED 记录及副本，不能冒充完整回滚。
            runCatching {
                check(file.lastModified() / 1000L == record.originalDateModifiedSec)
                val dates = MediaStoreUpdater.captureDates(context, mediaUriOf(record))
                check(MediaStoreUpdater.pointsTo(context, mediaUriOf(record), record.dataPath))
                check(dates.getAsLong(android.provider.MediaStore.MediaColumns.DATE_ADDED) == record.originalDateAddedSec &&
                    dates.getAsLong(android.provider.MediaStore.MediaColumns.DATE_MODIFIED) == record.originalDateModifiedSec)
                val taken = dates.getAsLong(android.provider.MediaStore.Images.Media.DATE_TAKEN)
                check(taken == null || taken <= 0 || taken == record.originalDateTakenMs)
                val backup = recycle.fileOf(record.backupRelPath)
                if (backup.exists()) {
                    check(FileUtils.sha256(backup) == record.originalSha256)
                    check(FileUtils.sha256(file) == record.originalSha256)
                    recycle.deleteBackupChecked(record.backupRelPath)
                }
                onRemoved(record.id)
            }.onFailure { if (it is CancellationException) throw it }
        }
        touched
    }

    fun untrackedBackups(records: List<CompressedItemEntity>): List<File> {
        val known = records.mapNotNull { it.backupRelPath }.toSet() +
            recovery.entries().flatMap { listOfNotNull(it.backupRelPath, it.safetyBackupRelPath) }
        return recycle.untracked(known)
    }

    /** 旧版本异常备份缺少原路径：复制到独立图集，不删除备份、不覆盖已有照片。 */
    suspend fun exportUntracked(records: List<CompressedItemEntity>): Int = withContext(Dispatchers.IO) {
        val directory = File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_PICTURES), "轻存恢复")
        check(directory.isDirectory || directory.mkdirs()) { "无法建立恢复图集，备份仍保留" }
        var exported = 0
        for (backup in untrackedBackups(records)) {
            var target = File(directory, backup.name)
            var suffix = 1
            while (!target.createNewFile()) target = File(directory,
                "${backup.nameWithoutExtension}_${suffix++}.${backup.extension}")
            try {
                val sha = FileUtils.copyWithSha256(backup, target)
                check(FileUtils.sha256(target) == sha) { "恢复副本校验失败，原始备份仍保留" }
            } catch (failure: Exception) {
                target.delete() // 仅清理本次 CREATE_NEW 创建的未完成副本。
                throw failure
            }
            MediaStoreUpdater.scanExisting(context, target.absolutePath)
            exported++
        }
        exported
    }

    /** 清理备份：只删备份，已压缩文件保持不动（E 需求：清理后仍显示为已压缩）。 */
    suspend fun purgeBackups(
        records: List<CompressedItemEntity>,
        onPurged: suspend (List<String>) -> Unit,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): RecycleBin.PurgeResult = withMediaLock {
        var freed = 0L
        var failed = 0
        var protected = 0
        val pending = ArrayList<String>(64)
        val protectedPaths = recovery.entries().flatMap { listOfNotNull(it.backupRelPath, it.safetyBackupRelPath) }.toSet()
        suspend fun flush() {
            if (pending.isEmpty()) return
            withContext(NonCancellable) { onPurged(pending.toList()) }
            pending.clear()
        }
        fun report(index: Int) {
            if ((index + 1) % 64 == 0 || index == records.lastIndex) onProgress(index + 1, records.size)
        }
        try {
            records.forEachIndexed { index, record ->
                currentCoroutineContext().ensureActive()
                val rel = record.backupRelPath
                if (rel != null) {
                    try {
                        if (rel in protectedPaths) { protected++; report(index); return@forEachIndexed }
                        val original = record.status == CompressedItemEntity.STATUS_RESTORED || record.status == CompressedItemEntity.STATUS_FAILED
                        val mediaPath = if (original)
                            record.originalPath.ifBlank { record.dataPath } else record.dataPath
                        val media = File(mediaPath)
                        val expectedSize = if (original)
                            record.originalSize else record.compressedSize
                        if (!media.isFile || media.length() <= 0 || media.length() != expectedSize) {
                            protected++; report(index); return@forEachIndexed
                        }
                        if (original && FileUtils.sha256(media) != record.originalSha256) {
                            protected++; report(index); return@forEachIndexed
                        }
                        freed += recycle.deleteBackupChecked(rel)
                        pending += record.id
                    } catch (_: Exception) {
                        failed++
                    }
                }
                if (pending.size >= 64) flush()
                report(index)
            }
        } finally {
            // 生命周期取消也必须登记本轮已删除的备份，不能把它们继续标成可还原。
            flush()
        }
        RecycleBin.PurgeResult(freed, failed, protected)
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
        private val mediaLock = Mutex()
        const val APP_VERSION = "0.1.0"
        const val RETENTION_DAYS = 30L
    }
}
