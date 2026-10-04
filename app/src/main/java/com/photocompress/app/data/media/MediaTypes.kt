package com.photocompress.app.data.media

/** 媒体大类（与原型 KIND 对应）。 */
enum class MediaKind(val label: String, val fullLabel: String) {
    PHOTO("照片", "普通照片"),
    LIVE_PHOTO("实况", "实况照片"),
    VIDEO("视频", "视频"),
}

/** 容器/格式，用于判类与跳过原因（D6 / D12 / C5）。 */
enum class ContainerFormat(val label: String) {
    JPEG("JPEG"),
    HEIC("HEIF"),
    PNG("PNG"),
    WEBP("WebP"),
    BMP("BMP"),
    GIF("GIF"),
    AVIF("AVIF"),
    DNG("RAW/DNG"),
    MP4("MP4"),
    MOV("MOV"),
    MKV("MKV"),
    WEBM("WebM"),
    THREE_GP("3GP"),
    TS("TS"),
    UNKNOWN("未知格式"),
}

/** 是否可处理；不可处理必须给出可见原因（C4 / AC12）。 */
sealed interface SupportDecision {
    data object Supported : SupportDecision
    data class Skipped(val reason: String) : SupportDecision
}

/** 压缩质量档位（D4 / F12）。 */
enum class QualityTier(val label: String) {
    HIGH("高质量"),
    BALANCED("平衡"),
    COMPACT("更省空间");

    companion object {
        fun fromName(value: String?): QualityTier =
            entries.firstOrNull { it.name == value } ?: BALANCED
    }
}
