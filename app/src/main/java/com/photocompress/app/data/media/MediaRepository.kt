package com.photocompress.app.data.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.photocompress.app.core.jpeg.JpegSegments
import com.photocompress.app.core.heif.HeicCompressor
import com.photocompress.app.core.png.PngCompressor
import com.photocompress.app.core.xmp.Mp4XmpMarker
import com.photocompress.app.core.xmp.PcXmp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MediaStore 枚举 + 头部探测，产出统一的媒体列表。
 *
 * 扫描分两种：
 * - [fullScan]：枚举整库并逐条探测，结果整体写入缓存。仅首次启动或手动重建时执行。
 * - [incrementalScan]：只对「新增 / 元数据变化 / 已删除」的条目做处理，未变化项直接复用缓存，
 *   避免每次启动都读一遍每个文件的头部与轨道信息。
 */
class MediaRepository(
    private val context: Context,
    private val cacheDao: MediaCacheDao,
) {

    private val imageCollection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val videoCollection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

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

    /** 增量对比只读轻量列，不做文件 IO。 */
    private val fingerprintProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_MODIFIED,
    )

    // ------------------------------------------------------------ 全量扫描

    suspend fun fullScan(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val total = countOf(imageCollection) + countOf(videoCollection)
            var done = 0
            onProgress(0, total)
            val result = ArrayList<MediaItem>(total.coerceAtMost(8192))
            queryImages(result, idFilter = null) { done++; onProgress(done, total) }
            queryVideos(result, idFilter = null) { done++; onProgress(done, total) }
            cacheDao.replaceAll(result.map { it.toCache() })
            result
        }

    // ------------------------------------------------------------ 增量扫描

    suspend fun incrementalScan(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val cachedList = cacheDao.all()
            val cachedFingerprints = cachedList.associate {
                it.key to CacheSnapshot(it.dataPath, it.dateAddedSec, it.dateModifiedSec)
            }
            val live = HashMap<String, CacheSnapshot>()
            live += fingerprints(imageCollection, isVideo = false)
            live += fingerprints(videoCollection, isVideo = true)

            val diff = diffCache(cachedFingerprints, live)

            // 1) 媒体库中已不存在 —— 从缓存删除
            diff.removedKeys.toList()
                .chunked(DB_CHUNK)
                .forEach { cacheDao.deleteByKeys(it) }

            // 2) 新增 / 变化 —— 只对这些条目重新做文件级探测
            val changedImageIds = idsOf(diff.changedKeys, "image:")
            val changedVideoIds = idsOf(diff.changedKeys, "video:")
            val total = changedImageIds.size + changedVideoIds.size
            var done = 0
            onProgress(0, total)
            val reprobed = ArrayList<MediaItem>(total)
            queryImages(reprobed, changedImageIds) { done++; onProgress(done, total) }
            queryVideos(reprobed, changedVideoIds) { done++; onProgress(done, total) }
            if (reprobed.isNotEmpty()) cacheDao.upsertAll(reprobed.map { it.toCache() })

            // 3) 合并：未变化且未删除的缓存条目 + 重新探测的结果
            val reprobedKeys = reprobed.mapTo(HashSet()) { it.cacheKey }
            val merged = ArrayList<MediaItem>(cachedList.size + reprobed.size)
            cachedList.forEach { cached ->
                if (cached.key !in diff.removedKeys && cached.key !in reprobedKeys) {
                    merged += cached.toMediaItem()
                }
            }
            merged += reprobed
            merged
        }

    /** 只读 _ID / 路径 / 时间戳，用于判定缓存是否仍然有效。 */
    private fun fingerprints(uri: Uri, isVideo: Boolean): Map<String, CacheSnapshot> {
        val out = HashMap<String, CacheSnapshot>()
        context.contentResolver.query(uri, fingerprintProjection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val path = c.getString(1) ?: continue
                if (path.isBlank()) continue
                out[mediaCacheKey(isVideo, id)] = CacheSnapshot(path, c.getLong(2), c.getLong(3))
            }
        }
        return out
    }

    private fun countOf(uri: Uri): Int =
        runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.count } ?: 0
        }.getOrDefault(0)

    // ------------------------------------------------------------ 逐条探测

    private fun queryImages(out: MutableList<MediaItem>, idFilter: Set<Long>?, onRow: () -> Unit) {
        for (selection in idSelections(idFilter)) {
            context.contentResolver.query(
                imageCollection,
                imageProjection,
                selection,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    parseImageRow(c, out)
                    onRow()
                }
            }
        }
    }

    private fun queryVideos(out: MutableList<MediaItem>, idFilter: Set<Long>?, onRow: () -> Unit) {
        for (selection in idSelections(idFilter)) {
            context.contentResolver.query(
                videoCollection,
                videoProjection,
                selection,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    parseVideoRow(c, out)
                    onRow()
                }
            }
        }
    }

    private fun parseImageRow(c: Cursor, out: MutableList<MediaItem>) {
        val id = c.getLong(0)
        val path = c.getString(1) ?: return
        if (path.isBlank()) return
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
        if (format == ContainerFormat.HEIC) {
            xmpCompressId = HeicCompressor.markerOf(File(path))?.id
        }
        val png = if (format == ContainerFormat.PNG) PngCompressor.probe(File(path)) else null
        val isLive = liveInfo != null
        val kind = if (isLive) MediaKind.LIVE_PHOTO else MediaKind.PHOTO
        val support = png?.skipReason?.let { SupportDecision.Skipped(it) } ?: MediaClassifier.decideImage(format, isLive)

        out += MediaItem(
            id = id,
            uri = ContentUris.withAppendedId(imageCollection, id),
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

    private fun parseVideoRow(c: Cursor, out: MutableList<MediaItem>) {
        val id = c.getLong(0)
        val path = c.getString(1) ?: return
        if (path.isBlank()) return
        val name = c.getString(5) ?: File(path).name
        val mime = c.getString(6) ?: "video/*"
        val size = File(path).takeIf { it.exists() }?.length() ?: c.getLong(7)
        val format = MediaClassifier.videoFormat(mime, name)

        val probe = if (format == ContainerFormat.MP4) {
            VideoProbeRunner.probe(path)
        } else {
            VideoProbe(null, 0, 0, 0, false, -1, -1, "非 MP4 容器，不做轨道探测")
        }
        // MediaExtractor 未给出色彩信息时兜底扫描 colr box（MP4 容器才有）
        val effectiveProbe = if (format == ContainerFormat.MP4 && probe.codec != null && probe.colorTransfer < 0) {
            val colr = VideoProbeRunner.scanColrInfo(File(path))
            if (colr.transfer in setOf(6, 7, 16, 18) || colr.primaries == VideoProbe.COLOR_STANDARD_BT2020) {
                probe.copy(
                    isTenBitHdr = true,
                    colorTransfer = if (colr.transfer in setOf(6, 7, 16, 18)) colr.transfer else probe.colorTransfer,
                    colorStandard = if (probe.colorStandard < 0) colr.primaries else probe.colorStandard,
                )
            } else probe
        } else probe

        // 判类时注入设备 HDR 保真能力：无 Main10 编码器则 HDR 源直接标记跳过（不降级）
        val support = MediaClassifier.decideVideo(format, effectiveProbe) { MediaClassifier.deviceCanPreserveHdr }
        // 视频的自有标记在 MP4 顶层 uuid box（D5），用于账本丢失后的识别
        val xmpCompressId = runCatching { Mp4XmpMarker.read(File(path))?.id }.getOrNull()

        out += MediaItem(
            id = id,
            uri = ContentUris.withAppendedId(videoCollection, id),
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

    // ------------------------------------------------------------ 辅助

    private fun idsOf(keys: Set<String>, prefix: String): Set<Long> =
        keys.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.substring(prefix.length).toLong() }
            .toHashSet()

    /** null 表示不过滤（全量）；空集表示无需查询。 */
    private fun idSelections(idFilter: Set<Long>?): List<String?> = when {
        idFilter == null -> listOf(null)
        idFilter.isEmpty() -> emptyList()
        idFilter.size <= ID_CHUNK -> listOf(idSelection(idFilter))
        else -> idFilter.chunked(ID_CHUNK).map { idSelection(it) }
    }

    private fun idSelection(ids: Collection<Long>): String =
        "${MediaStore.MediaColumns._ID} IN (${ids.joinToString(",")})"

    private companion object {
        const val ID_CHUNK = 800
        const val DB_CHUNK = 500
    }
}

private val MediaItem.cacheKey: String get() = mediaCacheKey(kind == MediaKind.VIDEO, id)

private fun MediaItem.toCache(): CachedMediaEntity = CachedMediaEntity(
    key = cacheKey,
    mediaStoreId = id,
    isVideo = kind == MediaKind.VIDEO,
    dataPath = dataPath,
    volumeName = volumeName,
    bucketId = bucketId,
    bucketName = bucketName,
    displayName = displayName,
    mimeType = mimeType,
    size = size,
    dateTakenMs = dateTakenMs,
    dateAddedSec = dateAddedSec,
    dateModifiedSec = dateModifiedSec,
    width = width,
    height = height,
    kind = kind.name,
    format = format.name,
    skipReason = skipReason,
    motionPhotoOffset = motionPhotoOffset,
    videoCodec = videoCodec,
    xmpCompressId = xmpCompressId,
)

private fun CachedMediaEntity.toMediaItem(): MediaItem {
    val video = isVideo
    val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    return MediaItem(
        id = mediaStoreId,
        uri = ContentUris.withAppendedId(collection, mediaStoreId),
        dataPath = dataPath,
        volumeName = volumeName,
        bucketId = bucketId,
        bucketName = bucketName,
        displayName = displayName,
        mimeType = mimeType,
        size = size,
        dateTakenMs = dateTakenMs,
        dateAddedSec = dateAddedSec,
        dateModifiedSec = dateModifiedSec,
        width = width,
        height = height,
        kind = runCatching { MediaKind.valueOf(kind) }.getOrDefault(MediaKind.PHOTO),
        format = runCatching { ContainerFormat.valueOf(format) }.getOrDefault(ContainerFormat.UNKNOWN),
        support = skipReason?.let { SupportDecision.Skipped(it) } ?: SupportDecision.Supported,
        motionPhotoOffset = motionPhotoOffset,
        videoCodec = videoCodec,
        xmpCompressId = xmpCompressId,
    )
}
