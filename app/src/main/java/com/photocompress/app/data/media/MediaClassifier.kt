package com.photocompress.app.data.media

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile

/** HDR 类型：由色彩传递特性（transfer）推断，用于匹配「内容 ↔ signaling」是否一致。 */
enum class HdrKind(val label: String) {
    NONE("SDR"),
    PQ("HDR10/PQ"),
    HLG("HLG"),
    OTHER("HDR（未标注传递特性）"),
}

/** HDR 保真等级：源与产物逐项比对后的结论。 */
enum class HdrFidelity { PRESERVED, DEGRADED, LOST }

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
    val profile: Int = -1,
    val bitDepth: Int = -1,
    val colorRange: Int = -1,
) {
    /** 是否 HEVC Main10 系（Main10 / Main10HDR10 / Main10HDR10Plus）。 */
    val isMain10Family: Boolean
        get() = profile in MAIN10_PROFILES || bitDepth >= 10

    /** 由传递特性推断的 HDR 类型。 */
    val hdrKind: HdrKind
        get() = when {
            colorTransfer == COLOR_TRANSFER_PQ || colorTransfer == COLOR_TRANSFER_PQ_ISO -> HdrKind.PQ
            colorTransfer == COLOR_TRANSFER_HLG || colorTransfer == COLOR_TRANSFER_HLG_ISO -> HdrKind.HLG
            isTenBitHdr -> HdrKind.OTHER
            else -> HdrKind.NONE
        }

    /** 是否 HDR：10bit 主档或带 HDR 传递特性/色彩标准。 */
    val isHdr: Boolean
        get() = isTenBitHdr || hdrKind != HdrKind.NONE

    companion object {
        val MAIN10_PROFILES = setOf(2, 4096, 8192)
        const val COLOR_TRANSFER_PQ = 6
        const val COLOR_TRANSFER_HLG = 7
        const val COLOR_TRANSFER_PQ_ISO = 16
        const val COLOR_TRANSFER_HLG_ISO = 18
        const val COLOR_STANDARD_BT2020 = 6
    }
}

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
        ContainerFormat.HEIC -> SupportDecision.Skipped("HEIC 原格式及元数据无法完整保留，已保留原片")
        ContainerFormat.PNG -> SupportDecision.Supported // 设置开关在 UI 候选及引擎入口独立检查。
        ContainerFormat.BMP -> SupportDecision.Skipped("BMP 非相机原生格式，本版本不处理")
        ContainerFormat.WEBP -> SupportDecision.Skipped("WebP 属第三方来源，本版本不处理")
        ContainerFormat.GIF -> SupportDecision.Skipped("动图（GIF）本版本不处理")
        ContainerFormat.AVIF -> SupportDecision.Skipped("AVIF 在 Android 平台无公开编码器，本版本不处理")
        ContainerFormat.DNG -> SupportDecision.Skipped("RAW/DNG 保留后期空间，不压缩")
        else -> SupportDecision.Skipped("未知图片格式，本版本不处理")
    }

    /**
     * 视频支持判定：只处理 MP4 容器。
     *
     * 10bit HDR **不再一律跳过**，但也不再降级为 SDR —— 按「保真或跳过」的策略。
     *
     * [canPreserveHdr] 是一个惰性提供器（默认 [deviceCanPreserveHdr]），**只在源确实为 HDR 时才会被求值**：
     * - 有 HEVC Main10 编码器 → HDR 源进入候选，压缩后再由 VideoTranscoder 校验保真，失败则跳过；
     * - 无 → 此处即标记跳过并给出原因，不产生任何改动原文件的机会。
     *
     * 做成惰性参数而非直接查 [MediaCodecList]：判类是纯逻辑，需保持 JVM 单测可跑
     * （`MediaCodecList` 在 JVM 测试环境是桩实现，非 HDR 源不应触碰它）。
     */
    fun decideVideo(
        format: ContainerFormat,
        probe: VideoProbe,
        canPreserveHdr: () -> Boolean = { deviceCanPreserveHdr },
    ): SupportDecision = when {
        format != ContainerFormat.MP4 -> SupportDecision.Skipped("${format.label} 容器本版本不处理")
        probe.codec == null -> SupportDecision.Skipped("无法解析视频轨道，本版本不处理")
        probe.isHdr && !canPreserveHdr() -> SupportDecision.Skipped(
            "无 HEVC Main10 编码器，无法保真压缩 HDR（${probe.hdrKind.label}），已跳过"
        )
        else -> SupportDecision.Supported
    }

    /**
     * 设备是否具备 HEVC Main10（HDR10）编码能力。
     * 结果在进程内缓存——编码器列表在同一设备上不会变化。
     *
     * 仅在运行时（Android）可求值；不要在 JVM 单测里触碰它。
     */
    val deviceCanPreserveHdr: Boolean by lazy {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        list.codecInfos.any { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(CODEC_HEVC, ignoreCase = true) } &&
                runCatching { info.getCapabilitiesForType(CODEC_HEVC) }.getOrNull()
                    ?.profileLevels
                    ?.any { it.profile in VideoProbe.MAIN10_PROFILES } == true
        }
    }

    /**
     * 比对源与产物的 HDR 属性，判断压缩后是否保留了原始 HDR 信息。
     *
     * 源码非 HDR 时直接判为 [HdrFidelity.PRESERVED]（无需比对）；
     * 源为 HDR 时逐项检查：传递特性（PQ/HLG 不可互换）、10bit 主档、BT.2020 色彩标准。
     * 任一不一致即降级，产物完全不带 HDR 标记则为丢失。
     */
    fun compareHdr(source: VideoProbe, output: VideoProbe): Pair<HdrFidelity, String> {
        if (!source.isHdr) return HdrFidelity.PRESERVED to ""
        val issues = mutableListOf<String>()
        if (source.hdrKind != HdrKind.NONE && output.hdrKind != source.hdrKind) {
            issues += "传递特性 ${source.hdrKind.label}→${output.hdrKind.label}"
        }
        if (source.isMain10Family && !output.isMain10Family) {
            issues += "10bit 主档丢失"
        }
        if (source.colorStandard == VideoProbe.COLOR_STANDARD_BT2020 &&
            output.colorStandard != VideoProbe.COLOR_STANDARD_BT2020
        ) {
            issues += "色彩标准 BT.2020 丢失"
        }
        if (output.hdrKind == HdrKind.NONE) {
            val detail = if (issues.isEmpty()) "产物未标注 HDR" else issues.joinToString("；")
            return HdrFidelity.LOST to "输出未保留 HDR（$detail）"
        }
        return if (issues.isEmpty()) HdrFidelity.PRESERVED to ""
        else HdrFidelity.DEGRADED to "HDR 信息不完整（${issues.joinToString("；")}）"
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
                val range = format.getIntOr(MediaFormat.KEY_COLOR_RANGE, -1)
                var profile = format.getIntOr(MediaFormat.KEY_PROFILE, -1)
                var bitDepth = format.getIntOr("bit-depth-luma", -1)
                // 兜底：HEVC 源若缺 profile / 位深，直读 hvcC（部分机型 MediaExtractor 不报告这两项）
                if (codec == MediaClassifier.CODEC_HEVC && (profile < 0 || bitDepth < 0)) {
                    val cfg = scanHevcConfig(File(path))
                    if (profile < 0) profile = cfg.profile
                    if (bitDepth < 0) bitDepth = cfg.bitDepthLuma
                }
                // HDR 判定：PQ(6/16)/HLG(7/18) 或 BT.2020(6)，或 HEVC Main10 系，或位深 >= 10
                val hdr = transfer in setOf(6, 7, 16, 18) || standard == 6 ||
                    (codec == MediaClassifier.CODEC_HEVC && profile in VideoProbe.MAIN10_PROFILES) || bitDepth >= 10
                VideoProbe(
                    codec = codec, width = width, height = height, durationMs = duration,
                    isTenBitHdr = hdr, colorTransfer = transfer, colorStandard = standard,
                    note = "profile=$profile bitDepth=$bitDepth transfer=$transfer standard=$standard range=$range",
                    profile = profile, bitDepth = bitDepth, colorRange = range,
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

    /** MP4 `colr` box 中的色彩描述（nclx/nclc）。未找到时为 [ColrInfo.NONE]。 */
    data class ColrInfo(val primaries: Int, val transfer: Int, val matrix: Int) {
        val present: Boolean get() = transfer >= 0

        companion object {
            val NONE = ColrInfo(-1, -1, -1)
        }
    }

    /**
     * 从 MP4 直读 HEVC 的 `hvcC` 配置盒，取 profile 与位深。
     *
     * MediaExtractor 在部分机型上不报告 `KEY_PROFILE` / `bit-depth-luma`，
     * 导致「HEVC Main10 但无 colr」的视频（如本仓库的 `vid_10bit_hdr.mp4`）漏判。
     * hvcC 布局（ISO 14496-15）：profile_space+tier+profile_idc(1) 后逐字节展开，
     * bitDepthLumaMinus8 在偏移 17。
     */
    data class HevcConfig(val profile: Int, val bitDepthLuma: Int) {
        val isMain10: Boolean
            get() = profile in VideoProbe.MAIN10_PROFILES || bitDepthLuma >= 10

        companion object {
            val NONE = HevcConfig(-1, -1)
        }
    }

    fun scanHevcConfig(file: File): HevcConfig {
        return runCatching {
            // hvcC 位于 moov/stsd，通常靠文件尾部；扫尾部 2MB 即可覆盖
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
                    val idx = indexOf(buf, "hvcC".toByteArray(Charsets.US_ASCII))
                    if (idx >= 0 && idx + 22 <= buf.size) {
                        val profile = buf[idx + 5].toInt() and 0x1F
                        val bitDepthLuma = (buf[idx + 21].toInt() and 0x07) + 8
                        return@runCatching HevcConfig(profile, bitDepthLuma)
                    }
                }
            }
            HevcConfig.NONE
        }.getOrDefault(HevcConfig.NONE)
    }

    /**
     * MediaExtractor 未报告色彩信息时的兜底：直接扫描 MP4 的 `colr` box。
     *
     * nclx 布局：`colr`(4) + type(4) + primaries(2) + transfer(2) + matrix(2) + full_range_flag(1)。
     * 只扫描文件首尾各 2MB（`colr` 位于 moov/stsd，通常靠前或靠后）。
     */
    fun scanColrInfo(file: File): ColrInfo {
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
                    if (idx >= 0 && idx + 14 <= buf.size) {
                        val type = String(buf, idx + 4, 4, Charsets.US_ASCII)
                        if (type == "nclx" || type == "nclc") {
                            return@runCatching ColrInfo(
                                primaries = u16(buf, idx + 8),
                                transfer = u16(buf, idx + 10),
                                matrix = u16(buf, idx + 12),
                            )
                        }
                    }
                }
            }
            ColrInfo.NONE
        }.getOrDefault(ColrInfo.NONE)
    }

    private fun u16(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

    /** 只取传递特性的便捷入口（PQ/HLG 判定用）。 */
    fun scanColrBox(file: File): Int = scanColrInfo(file).transfer

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
