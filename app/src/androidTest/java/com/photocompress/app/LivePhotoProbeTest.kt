package com.photocompress.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 实况照片「系统能否识别内嵌视频」的探针。
 *
 * 背景：实况照片压缩后相册无法播放。静态解析显示容器/XMP/MPF/MP4 全部自洽，
 * 因此需要判定**系统媒体框架本身**能否从文件里找到并解码内嵌视频。
 *
 * 对压缩前后两份文件分别用 MediaExtractor / MediaMetadataRetriever 探测，
 * 差异即为根因所在。
 *
 * 执行：
 * ```
 * adb shell am instrument -w -e class com.photocompress.app.LivePhotoProbeTest \
 *   com.photocompress.app.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d -s PCLIVEPROBE
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LivePhotoProbeTest {

    private val tag = "PCLIVEPROBE"

    /** 压缩后的实况照片（媒体库中的实际文件）。 */
    private val afterPath = "/storage/emulated/0/DCIM/MyAlbums/测试/IMG20261003164540.jpg"

    /** 同一条的原始备份（应用私有回收站）。 */
    private val beforePath =
        "/data/data/com.photocompress.app/files/recycle/" +
            "3a7831b4-8578-48fd-bbc5-5dfeb815a1b9/IMG20261003164540.jpg"

    @Test
    fun probeBoth() {
        probe(beforePath, "压缩前(备份)")
        Log.i(tag, "")
        probe(afterPath, "压缩后(当前)")
        Log.i(tag, "")
        // 再对第二组做一次，确认不是个例
        probe(
            "/data/data/com.photocompress.app/files/recycle/" +
                "16df67ca-57e9-4b14-87fb-be5fe06aca8a/IMG20261003164719.jpg",
            "压缩前(备份·第二条)",
        )
        Log.i(tag, "")
        probe("/storage/emulated/0/DCIM/MyAlbums/测试/IMG20261003164719.jpg", "压缩后(当前·第二条)")
    }

    private fun probe(path: String, label: String) {
        val f = File(path)
        if (!f.exists()) {
            Log.w(tag, "$label: 文件不存在 $path")
            return
        }
        Log.i(tag, "=== $label  大小=${f.length()} ===")

        // 1) MediaExtractor：能否发现轨道
        runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(path)
                Log.i(tag, "  MediaExtractor: trackCount=${ex.trackCount}")
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME)
                    val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else -1L
                    Log.i(tag, "    [$i] mime=$mime durationUs=$dur")
                }
            } finally {
                runCatching { ex.release() }
            }
        }.onFailure { Log.e(tag, "  MediaExtractor 失败: ${it::class.simpleName} ${it.message}") }

        // 2) MediaMetadataRetriever：能否取到时长与首帧
        runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(path)
                val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val hasVideo = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                Log.i(tag, "  Retriever: duration=$dur width=$w height=$h hasVideo=$hasVideo")
                val frame = mmr.getFrameAtTime(1_144_117, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                Log.i(tag, "  Retriever: 1.144s 处取帧 = ${if (frame != null) "${frame.width}x${frame.height}" else "null"}")
            } finally {
                runCatching { mmr.release() }
            }
        }.onFailure { Log.e(tag, "  Retriever 失败: ${it::class.simpleName} ${it.message}") }
    }
}
