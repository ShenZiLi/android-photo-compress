package com.photocompress.app

import android.content.ContentValues
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证能否直接更新 oplus 私有的实况字段。
 *
 * 根因链：压缩后原地改写并**恢复 mtime**（AC4 要求）→ 扫描器认为文件未变、
 * 跳过重解析 → MediaStore 的 `_size` / `o_video_size` 仍是旧值 →
 * oplus 相册按 `o_video_size` 定位内嵌视频，越界 → 无法播放。
 *
 * 既然 `scanFile` 推不动，就看能否绕过扫描器直接改列。
 */
@RunWith(AndroidJUnit4::class)
class OplusColumnUpdateProbeTest {

    private val tag = "PCOLUPDATE"
    private val uri = Uri.parse("content://media/external_primary/images/media/1000033648")

    @Test
    fun tryUpdateOplusColumns() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        fun attempt(name: String, values: ContentValues) {
            runCatching {
                val n = ctx.contentResolver.update(uri, values, null, null)
                Log.i(tag, "$name -> 影响行数=$n")
            }.onFailure {
                Log.e(tag, "$name -> 异常 ${it::class.simpleName}: ${it.message}")
            }
        }

        // 1) 标准 _size
        attempt("update _size", ContentValues().apply { put("_size", 8893299L) })
        // 2) oplus 私有：内嵌视频长度
        attempt("update o_video_size", ContentValues().apply { put("o_video_size", 5864993L) })
        // 3) oplus 私有：封面时间戳（顺带看私有列是否整体可写）
        attempt("update o_cover_time_stamps", ContentValues().apply { put("o_cover_time_stamps", 1144117L) })
        // 4) 两者一起
        attempt(
            "update 组合",
            ContentValues().apply {
                put("_size", 8893299L)
                put("o_video_size", 5864993L)
            },
        )
    }
}
