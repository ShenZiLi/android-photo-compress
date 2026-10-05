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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface CompressOutcome {
    data class Success(val record: CompressedItemEntity) : CompressOutcome
    data class Skipped(val reason: String) : CompressOutcome
    data class Failed(val reason: String) : CompressOutcome
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

    suspend fun compress(item: MediaItem, tier: QualityTier): CompressOutcome = withContext(Dispatchers.IO) {
        runCatching {
            when (item.kind) {
                MediaKind.PHOTO -> if (item.format == ContainerFormat.HEIC) {
                    compressHeic(item, tier)
                } else {
                    compressPhoto(item, tier)
                }
                MediaKind.LIVE_PHOTO -> compressLivePhoto(item, tier)
                MediaKind.VIDEO -> compressVideo(item, tier)
            }
        }.getOrElse { t ->
            CompressOutcome.Failed("异常 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- HEIC（格式转换）

    /**
     * HEIC/HEIF 压缩：平台无法把元信息写回 HEIF 容器，因此只能**转为 JPEG**。
     * 同目录、同图集，文件名扩展名由 .heic 变为 .jpg；原文件进回收站，还原时按原路径写回。
     */
    private suspend fun compressHeic(item: MediaItem, tier: QualityTier): CompressOutcome {
        val file = File(item.dataPath)
        if (!file.exists()) return CompressOutcome.Failed("文件不存在")
        val originalSize = file.length()
        val originalModifiedSec = (file.lastModified() / 1000L).takeIf { it > 0 } ?: item.dateModifiedSec
        val originalSha = FileUtils.sha256(file)
        val id = UUID.randomUUID().toString()
        // HEIC 的编码效率高于 JPEG：用同等质量转 JPEG 往往会变大，
        // 因此这里在同档位上再降一档质量，尽量取得体积收益（仍保留「不变小就跳过」的保护）。
        val quality = (JpegCompressor.qualityFor(tier) - 12).coerceAtLeast(70)

        val temp = File(context.cacheDir, "pc_heic_${System.currentTimeMillis()}.jpg")
        val convertError = HeicCompressor.convert(file, temp, quality)
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
        return try {
            backupRel = recycle.backup(id, file)
            val backupSize = recycle.fileOf(backupRel).length()

            FileUtils.copy(temp, target)
            target.setLastModified(file.lastModified())

            // 先物理删除原 HEIC，再重建媒体库索引（删除旧行会连带删文件，顺序不能颠倒）
            file.delete()
            val newUri = MediaStoreUpdater.reindex(context, item.uri, target.absolutePath)
            val newId = newUri?.let { runCatching { android.content.ContentUris.parseId(it) }.getOrNull() } ?: 0L

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
            CompressOutcome.Success(record)
        } catch (t: Throwable) {
            runCatching { target.delete() }
            runCatching { MediaStoreUpdater.reindex(context, null, item.dataPath) }
            CompressOutcome.Failed("HEIC 转换失败：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            temp.delete()
        }
    }

    // ---------------------------------------------------------------- 普通照片

    private suspend fun compressPhoto(item: MediaItem, tier: QualityTier): CompressOutcome {
        val file = File(item.dataPath)
        if (!file.exists()) return CompressOutcome.Failed("文件不存在")
        if (item.format != ContainerFormat.JPEG) {
            return CompressOutcome.Skipped("${item.format.label} 本版本不处理")
        }
        val original = file.readBytes()
        if (!JpegSegments.isJpeg(original)) return CompressOutcome.Skipped("不是有效 JPEG")

        val mpfPayload = JpegSegments.mpfPayloadOf(original)
        if (mpfPayload != null) {
            val n = MpfRewriter.numberOfImages(mpfPayload)
            if (n != 1) return CompressOutcome.Skipped("含 $n 图 MPF 多图结构，本版本不处理")
        }

        val xmp = JpegSegments.xmpTextOf(original)
        val containerItems = LivePhotoDetector.parseContainerItems(xmp ?: "")
        if (containerItems.any { it.semantic == "MotionPhoto" } || containerItems.any { it.semantic == "GainMap" }) {
            // 实况照片 / Ultra HDR 被误判为普通照片时，走容器重组路径
            return compressLivePhoto(item, tier)
        }

        val quality = JpegCompressor.qualityFor(tier)
        val encoded = JpegCompressor.compressJpeg(original, quality)
            ?: return CompressOutcome.Failed("JPEG 解码失败")

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

        val temp = File(context.cacheDir, "pc_photo_${System.currentTimeMillis()}.jpg")
        temp.writeBytes(finalBytes)
        return commit(item, temp, tier, codecUsed = "JPEG q=${quality}")
    }

    // ---------------------------------------------------------------- 实况照片

    private suspend fun compressLivePhoto(item: MediaItem, tier: QualityTier): CompressOutcome {
        val file = File(item.dataPath)
        if (!file.exists()) return CompressOutcome.Failed("文件不存在")
        val original = file.readBytes()
        if (!JpegSegments.isJpeg(original)) return CompressOutcome.Skipped("不是有效 JPEG")

        val xmp = JpegSegments.xmpTextOf(original) ?: return CompressOutcome.Skipped("缺少 XMP，无法解析实况结构")
        val items = LivePhotoDetector.parseContainerItems(xmp)
        val plan = LivePhotoContainer.plan(file, items)
            ?: return CompressOutcome.Skipped("无法解析实况照片容器")

        val mpfPayload = JpegSegments.mpfPayloadOf(original)
        if (mpfPayload != null) {
            val n = MpfRewriter.numberOfImages(mpfPayload)
            if (n != 1 && n != 2) return CompressOutcome.Skipped("含 $n 图 MPF 多图结构，本版本不处理")
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

        // 1) 主图重编码
        val quality = JpegCompressor.qualityFor(tier)
        val encodedPrimary = JpegCompressor.compressJpeg(primaryBytes, quality)
            ?: return CompressOutcome.Failed("主图解码失败")

        // 2) 内嵌视频：**只有「单个普通 MP4」才重编码**。
        //    oplus/realme 的尾段常是厂商私有复合结构（MP4 + 私有块 + MP4），
        //    整段替换会让相册无法播放（实机验证），故此类一律原样保留。
        val canReencode = plan.hasMotion && motionBytes != null && LivePhotoContainer.isPlainMp4(motionBytes)
        var newMotionFile: File? = null
        var motionNote: String = when {
            !plan.hasMotion -> "无内嵌视频"
            !canReencode -> "内嵌视频原样保留（厂商私有封装）"
            else -> "内嵌视频重编码"
        }
        if (canReencode) {
            val srcMotion = File(context.cacheDir, "pc_motion_src_${System.currentTimeMillis()}.mp4")
            srcMotion.writeBytes(motionBytes!!)
            val dstMotion = File(context.cacheDir, "pc_motion_dst_${System.currentTimeMillis()}.mp4")
            val res = VideoTranscoder.transcode(srcMotion, dstMotion, tier)
            if (res.success && dstMotion.exists() && dstMotion.length() < motionBytes.size) {
                // 内嵌视频也要保住原有元数据（相机信息等）
                Mp4Metadata.inject(dstMotion, srcMotion)
                newMotionFile = dstMotion
            } else {
                // 转码失败或没变小：退回「原样保留」，不影响主图收益
                dstMotion.delete()
                motionNote = "内嵌视频原样保留（重编码未获益：${res.reason ?: "体积未减小"}）"
            }
            srcMotion.delete()
        }

        try {
            // 增益图**原样保留**：它是 HDR/ProXDR 重建的依据，重编码会引入偏差（C6 / D10）
            val gainLen = gainBytes?.size?.toLong() ?: 0L
            val motionLen = newMotionFile?.length() ?: (motionBytes?.size?.toLong() ?: 0L)

            // 3) XMP：只在长度真的变化时就地改写数字，并注入自有标记（不改结构）
            val changed = HashMap<String, Long>()
            if (gainBytes != null && gainLen != plan.gainMapLength) changed["GainMap"] = gainLen
            if (newMotionFile != null && motionLen != plan.motionLength) changed["MotionPhoto"] = motionLen
            var xmpOut = if (changed.isEmpty()) xmp else LivePhotoContainer.rewriteItemLengths(xmp, changed)
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
            newMotionFile?.let { out.write(it.readBytes()) } ?: motionBytes?.let { out.write(it) }
            val assembled = out.toByteArray()

            LivePhotoContainer.verify(assembled)?.let {
                return CompressOutcome.Skipped("重组校验失败：$it")
            }
            if (plan.hasMotion) {
                val mp4Start = assembled.size - motionLen.toInt()
                val magic = String(assembled, mp4Start + 4, 4, Charsets.US_ASCII)
                if (magic != "ftyp") return CompressOutcome.Skipped("内嵌视频定位校验失败")
            }
            if (assembled.size >= original.size) {
                return CompressOutcome.Skipped("压缩后体积未减小（${original.size} → ${assembled.size}）")
            }

            val temp = File(context.cacheDir, "pc_live_${System.currentTimeMillis()}.jpg")
            temp.writeBytes(assembled)
            return commit(item, temp, tier, codecUsed = "JPEG q=$quality + 增益图原样 + $motionNote")
        } finally {
            newMotionFile?.delete()
        }
    }

    // ---------------------------------------------------------------- 视频

    private suspend fun compressVideo(item: MediaItem, tier: QualityTier): CompressOutcome {
        val file = File(item.dataPath)
        if (!file.exists()) return CompressOutcome.Failed("文件不存在")
        if (item.format != ContainerFormat.MP4) {
            return CompressOutcome.Skipped("${item.format.label} 容器本版本不处理")
        }
        val dst = File(context.cacheDir, "pc_video_${System.currentTimeMillis()}.mp4")
        val res = VideoTranscoder.transcode(file, dst, tier)
        if (!res.success || !dst.exists()) {
            dst.delete()
            return CompressOutcome.Skipped(res.reason ?: "转码失败")
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
        return commit(item, dst, tier, codecUsed = MediaClassifierCodec.label(probe.codec))
    }

    // ---------------------------------------------------------------- 提交（备份 → 原地替换 → 校验 → 回滚）

    private suspend fun commit(
        item: MediaItem,
        tempContent: File,
        tier: QualityTier,
        codecUsed: String?,
    ): CompressOutcome {
        val file = File(item.dataPath)
        if (!file.exists()) {
            tempContent.delete()
            return CompressOutcome.Failed("原文件不存在")
        }
        val originalSize = file.length()
        val mtimeMs = file.lastModified()
        val originalSha = FileUtils.sha256(file)
        val id = UUID.randomUUID().toString()
        var backupRel: String? = null

        return try {
            backupRel = recycle.backup(id, file)
            val backupSize = recycle.fileOf(backupRel).length()

            InPlaceRewriter.writeFrom(file, tempContent, mtimeMs)

            // 写回校验
            if (!file.exists() || file.length() != tempContent.length()) {
                throw IllegalStateException("写入后长度不一致")
            }

            MediaStoreUpdater.refresh(
                context, item.uri, item.dataPath,
                item.dateTakenMs, item.dateAddedSec, item.dateModifiedSec,
            )

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
            CompressOutcome.Success(record)
        } catch (t: Throwable) {
            // 回滚：用备份原地恢复
            if (backupRel != null) {
                runCatching {
                    recycle.restore(backupRel, file, mtimeMs)
                    MediaStoreUpdater.refresh(
                        context, item.uri, item.dataPath,
                        item.dateTakenMs, item.dateAddedSec, item.dateModifiedSec,
                    )
                }
            }
            CompressOutcome.Failed("写入失败已回滚：${t.javaClass.simpleName}: ${t.message}")
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
    suspend fun purgeBackups(records: List<CompressedItemEntity>): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        records.forEach { record ->
            record.backupRelPath?.let { rel ->
                val f = recycle.fileOf(rel)
                if (f.exists()) freed += f.length()
                recycle.delete(rel)
            }
        }
        freed
    }

    fun recycleBinSize(): Long = recycle.totalSize()

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
