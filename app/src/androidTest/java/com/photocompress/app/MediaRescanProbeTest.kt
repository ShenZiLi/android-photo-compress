package com.photocompress.app

import android.media.MediaScannerConnection
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 验证「单文件重扫能否刷新 MediaStore 的 oplus 私有字段」。
 *
 * 背景：实况照片压缩后相册无法播放。已查明根因是 MediaStore 里的
 * `o_video_size` 仍是压缩前的值（oplus 相册靠它定位内嵌视频），
 * 而 `contentResolver.update` 对 `_size` 这类列会被 MediaProvider 静默忽略。
 *
 * 本探针对已压缩的文件触发一次 MediaScannerConnection.scanFile，
 * 用于确认扫描器会不会把 `o_video_size` / `_size` 刷新为新值。
 *
 * 执行后配合 adb 查询字段（见提交说明）。
 */
@RunWith(AndroidJUnit4::class)
class MediaRescanProbeTest {

    private val tag = "PCRESCAN"

    @Test
    fun rescanCompressedLivePhoto() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val path = "/storage/emulated/0/DCIM/MyAlbums/测试/IMG20261003164540.jpg"

        val latch = CountDownLatch(1)
        Log.i(tag, "触发重扫: $path")
        runCatching {
            MediaScannerConnection.scanFile(ctx, arrayOf(path), null) { p, uri ->
                Log.i(tag, "扫描回调: path=$p uri=$uri")
                latch.countDown()
            }
        }.onFailure { Log.e(tag, "scanFile 失败: ${it.message}") }
        val ok = latch.await(25, TimeUnit.SECONDS)
        Log.i(tag, "扫描完成=$ok")
    }
}
