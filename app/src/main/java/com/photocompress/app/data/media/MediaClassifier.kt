package com.photocompress.app.data.media

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile

/** 视频轨道探测结果（判类用，不做解码）。 */
data class VideoProbe(
    val codec: String?,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val isTenBitHdr: Boolean,
    val colorTransfer: Int,
    val colorStandard: Int,
    val note: String,
)

/**
 * 媒体判类：决定每条媒体属于哪一类、是否可处理，以及不可处理时的原因。
 * 依据 prd.md 的 D6 / D11 / D12 与 C5。
 */
object MediaClassifier {

    const val CODEC_AVC = "video/avc"
    const val CODEC_HEVC = "video/hevc"
    const val CODEC_AV1 = "video/av01"
    const val CODEC_VP9 = "video/x-vnd.on2.vp9"

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun imageFormat(mime: String, name: String): ContainerFormat {
        val ext = extensionOf(name)
        return when {
            mime == "image/jpeg" || ext == "jpg" || ext == "jpeg" -> ContainerFormat.JPEG
            mime == "image/heic" || mime == "image/heif" || ext == "heic" || ext == "heif" -> ContainerFormat.HEIC
            mime == "image/png" || ext == "png" -> ContainerFormat.PNG
            mime == "image/webp" || ext == "webp" -> ContainerFormat.WEBP
            mime in setOf("image/bmp", "image/x-ms-bmp") || ext == "bmp" -> ContainerFormat.BMP
            mime == "image/gif" || ext == "gif" -> ContainerFormat.GIF
            mime == "image/avif" || ext == "avif" -> ContainerFormat.AVIF
            ext in setOf("dng", "raw", "arw", "cr2", "nef", "orf", "rw2") -> ContainerFormat.DNG
            else -> ContainerFormat.UNKNOWN
        }
    }

    fun videoFormat(mime: String, name: String): ContainerFormat {
        val ext = extensionOf(name)
        return when {
            ext == "mp4" || ext == "m4v" -> ContainerFormat.MP4
            ext == "mov" -> ContainerFormat.MOV
            ext == "mkv" -> ContainerFormat.MKV
            ext == "webm" -> ContainerFormat.WEBM
            ext == "3gp" || ext == "3gpp" -> ContainerFormat.THREE_GP
            ext == "ts" || ext == "m2ts" -> ContainerFormat.TS
            mime == "video/mp4" -> ContainerFormat.MP4
            mime == "video/quicktime" -> ContainerFormat.MOV
            mime == "video/webm" -> ContainerFormat.WEBM
            mime == "video/x-matroska" -> ContainerFormat.MKV
            else -> ContainerFormat.UNKNOWN
        }
    }

    /** 图片（含实况主图）的支持判定。 */
    fun decideImage(format: ContainerFormat, isLivePhoto: Boolean): SupportDecision = when (format) {
        ContainerFormat.JPEG -> SupportDecision.Supported
        // HEIF 无法把 EXIF/XMP 写回容器，改为「转为 JPEG 并搬运元信息」的方式压缩（见 HeicCompressor）
        ContainerFormat.HEIC -> SupportDecision.Supported
        ContainerFormat.PNG -> SupportDecision.Skipped("PNG 是无损格式，本版本不处理")
        ContainerFormat.BMP -> SupportDecision.Skipped("BMP 非相机原生格式，本版本不处理")
        ContainerFormat.WEBP -> SupportDecision.Skipped("WebP 属第三方来源，本版本不处理")
        ContainerFormat.GIF -> SupportDecision.Skipped("动图（GIF）本版本不处理")
        ContainerFormat.AVIF -> SupportDecision.Skipped("AVIF 在 Android 平台无公开编码器，本版本不处理")
        ContainerFormat.DNG -> SupportDecision.Skipped("RAW/DNG 保留后期空间，不压缩")
        else -> SupportDecision.Skipped("未知图片格式，本版本不处理")
    }

    /** 视频支持判定：只处理 MP4 容器，10-bit HDR 跳过（D11 / C5）。 */
    fun decideVideo(format: ContainerFormat, probe: VideoProbe): SupportDecision = when {
        format != ContainerFormat.MP4 -> SupportDecision.Skipped("${format.label} 容器本版本不处理")
        probe.codec == null -> SupportDecision.Skipped("无法解析视频轨道，本版本不处理")
        probe.isTenBitHdr -> SupportDecision.Skipped("10-bit HDR 视频本版本不处理")
        else -> SupportDecision.Supported
    }

    /** 目标编码的展示名。 */
    fun codecLabel(codec: String?): String = when (codec) {
        CODEC_AVC -> "H.264"
        CODEC_HEVC -> "HEVC"
        CODEC_AV1 -> "AV1"
        CODEC_VP9 -> "VP9"
        null -> "未知"
        else -> codec
    }
}

/** 只读探测视频轨道（不做解码），用于判类与压缩前检查。 */
object VideoProbeRunner {

    fun probe(path: String): VideoProbe {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (format == null) {
                VideoProbe(null, 0, 0, 0, false, -1, -1, "无视频轨道")
            } else {
                extractor.selectTrack(trackIndex)
                val codec = format.getString(MediaFormat.KEY_MIME)
                val width = format.getIntOr(MediaFormat.KEY_WIDTH, 0)
                val height = format.getIntOr(MediaFormat.KEY_HEIGHT, 0)
                val duration = format.getLongOr(MediaFormat.KEY_DURATION, 0L) / 1000
                val transfer = format.getIntOr(MediaFormat.KEY_COLOR_TRANSFER, -1)
                val standard = format.getIntOr(MediaFormat.KEY_COLOR_STANDARD, -1)
                val profile = format.getIntOr(MediaFormat.KEY_PROFILE, -1)
                val bitDepth = format.getIntOr("bit-depth-luma", -1)
                // HDR 判定：PQ(6)/HLG(7) 或 BT.2020(6)，或 HEVC Main10(profile 2)，或位深 >= 10
                val hdr = transfer == 6 || transfer == 7 || standard == 6 ||
                    (codec == MediaClassifier.CODEC_HEVC && profile == 2) || bitDepth >= 10
                VideoProbe(
                    codec = codec, width = width, height = height, durationMs = duration,
                    isTenBitHdr = hdr, colorTransfer = transfer, colorStandard = standard,
                    note = "profile=$profile bitDepth=$bitDepth transfer=$transfer standard=$standard",
                )
            }
        } catch (t: Throwable) {
            VideoProbe(null, 0, 0, 0, false, -1, -1, "异常 ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.getIntOr(key: String, def: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(def) else def

    private fun MediaFormat.getLongOr(key: String, def: Long): Long =
        if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(def) else def

    /** MediaExtractor 未报告色彩信息时的兜底：直接扫描 MP4 的 `colr` box。 */
    fun scanColrBox(file: File): Int {
        return runCatching {
            val len = file.length()
            val window = 2L * 1024 * 1024
            val ranges = listOf(0L to minOf(window, len), maxOf(0L, len - window) to len)
            for ((start, end) in ranges) {
                if (end <= start) continue
                RandomAccessFile(file, "r").use { raf ->
                    val size = (end - start).toInt()
                    val buf = ByteArray(size)
                    raf.seek(start)
                    raf.readFully(buf)
                    val idx = indexOf(buf, "colr".toByteArray(Charsets.US_ASCII))
                    if (idx >= 0 && idx + 12 <= buf.size) {
                        val type = String(buf, idx + 4, 4, Charsets.US_ASCII)
                        if (type == "nclx" || type == "nclc") {
                            val transfer = ((buf[idx + 8].toInt() and 0xFF) shl 8) or (buf[idx + 9].toInt() and 0xFF)
                            return@runCatching transfer
                        }
                    }
                }
            }
            -1
        }.getOrDefault(-1)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
