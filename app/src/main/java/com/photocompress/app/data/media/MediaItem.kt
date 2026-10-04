package com.photocompress.app.data.media

import android.net.Uri

/** 媒体库中的一条媒体（由 MediaStore 枚举 + 头部探测得到）。 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val dataPath: String,
    val volumeName: String,
    val bucketId: Long,
    val bucketName: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val dateTakenMs: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val width: Int,
    val height: Int,
    val kind: MediaKind,
    val format: ContainerFormat,
    val support: SupportDecision,
    /** 实况照片内嵌 MP4 的起始偏移；非实况为 null。 */
    val motionPhotoOffset: Long? = null,
    /** 视频编码名（video 专属）。 */
    val videoCodec: String? = null,
) {
    val compressible: Boolean get() = support is SupportDecision.Supported
    val skipReason: String? get() = (support as? SupportDecision.Skipped)?.reason
}
