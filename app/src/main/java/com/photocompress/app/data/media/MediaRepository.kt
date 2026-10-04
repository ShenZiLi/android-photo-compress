package com.photocompress.app.data.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.photocompress.app.core.jpeg.JpegSegments
import com.photocompress.app.core.xmp.Mp4XmpMarker
import com.photocompress.app.core.xmp.PcXmp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** MediaStore 枚举 + 头部探测，产出统一的媒体列表。 */
class MediaRepository(private val context: Context) {

    private val imageProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.VOLUME_NAME,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
    )

    private val videoProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.VOLUME_NAME,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.Video.Media.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
    )

    suspend fun scan(): List<MediaItem> = withContext(Dispatchers.IO) {
        val result = ArrayList<MediaItem>(256)
        queryImages(result)
        queryVideos(result)
        result
    }

    private fun queryImages(out: MutableList<MediaItem>) {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection, null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val path = c.getString(1) ?: continue
                if (path.isBlank()) continue
                val name = c.getString(5) ?: File(path).name
                val mime = c.getString(6) ?: "image/*"
                // 直接读文件大小：原地改写后 MediaProvider 的 _size 可能尚未刷新（AC9 需要真实值）
                val size = File(path).takeIf { it.exists() }?.length() ?: c.getLong(7)
                val format = MediaClassifier.imageFormat(mime, name)

                var liveInfo: LivePhotoDetector.LiveInfo? = null
                var xmpCompressId: String? = null
                if (format == ContainerFormat.JPEG) {
                    // 头部只读一次，同时用于实况结构解析与自有 XMP 标记识别（F7 / AC5）
                    val header = LivePhotoDetector.readHeaderBytes(File(path))
                    if (header != null) {
                        liveInfo = runCatching {
                            LivePhotoDetector.detectFromHeader(header, File(path).length())
                        }.getOrNull()
                        xmpCompressId = runCatching {
                            JpegSegments.xmpTextOf(header)?.let { PcXmp.read(it)?.id }
                        }.getOrNull()
                    }
                }
                val isLive = liveInfo != null
                val kind = if (isLive) MediaKind.LIVE_PHOTO else MediaKind.PHOTO
                val support = MediaClassifier.decideImage(format, isLive)

                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    dataPath = path,
                    volumeName = c.getString(2) ?: "external_primary",
                    bucketId = c.getLong(3),
                    bucketName = c.getString(4)?.takeIf { it.isNotBlank() }
                        ?: File(path).parentFile?.name ?: "内部存储",
                    displayName = name,
                    mimeType = mime,
                    size = size,
                    dateTakenMs = c.getLong(8).takeIf { it > 0 } ?: (c.getLong(9) * 1000L),
                    dateAddedSec = c.getLong(9),
                    dateModifiedSec = c.getLong(10),
                    width = c.getInt(11),
                    height = c.getInt(12),
                    kind = kind,
                    format = format,
                    support = support,
                    motionPhotoOffset = liveInfo?.motionPhotoOffset,
                    xmpCompressId = xmpCompressId,
                )
            }
        }
    }

    private fun queryVideos(out: MutableList<MediaItem>) {
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection, null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val path = c.getString(1) ?: continue
                if (path.isBlank()) continue
                val name = c.getString(5) ?: File(path).name
                val mime = c.getString(6) ?: "video/*"
                val size = File(path).takeIf { it.exists() }?.length() ?: c.getLong(7)
                val format = MediaClassifier.videoFormat(mime, name)

                val probe = if (format == ContainerFormat.MP4) {
                    VideoProbeRunner.probe(path)
                } else {
                    VideoProbe(null, 0, 0, 0, false, -1, -1, "非 MP4 容器，不做轨道探测")
                }
                // MediaExtractor 未给出色彩信息时兜底扫描 colr box
                val effectiveProbe = if (format == ContainerFormat.MP4 && probe.codec != null &&
                    probe.colorTransfer < 0 && !probe.isTenBitHdr
                ) {
                    val transfer = VideoProbeRunner.scanColrBox(File(path))
                    if (transfer == 6 || transfer == 7 || transfer == 16 || transfer == 18) {
                        probe.copy(isTenBitHdr = true, colorTransfer = transfer)
                    } else probe
                } else probe

                val support = MediaClassifier.decideVideo(format, effectiveProbe)
                // 视频的自有标记在 MP4 顶层 uuid box（D5），用于账本丢失后的识别
                val xmpCompressId = runCatching { Mp4XmpMarker.read(File(path))?.id }.getOrNull()

                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    dataPath = path,
                    volumeName = c.getString(2) ?: "external_primary",
                    bucketId = c.getLong(3),
                    bucketName = c.getString(4)?.takeIf { it.isNotBlank() }
                        ?: File(path).parentFile?.name ?: "内部存储",
                    displayName = name,
                    mimeType = mime,
                    size = size,
                    dateTakenMs = c.getLong(8).takeIf { it > 0 } ?: (c.getLong(9) * 1000L),
                    dateAddedSec = c.getLong(9),
                    dateModifiedSec = c.getLong(10),
                    width = c.getInt(11).takeIf { it > 0 } ?: effectiveProbe.width,
                    height = c.getInt(12).takeIf { it > 0 } ?: effectiveProbe.height,
                    kind = MediaKind.VIDEO,
                    format = format,
                    support = support,
                    videoCodec = effectiveProbe.codec,
                    xmpCompressId = xmpCompressId,
                )
            }
        }
    }
}
