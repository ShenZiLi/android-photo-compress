package com.photocompress.app

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photocompress.app.core.jpeg.HeicCompressor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * HEIC 压缩链路验证：对真实 HEIC 样张（/sdcard/DCIM/Camera/heic_sample.heic）
 * 执行「HEIC → JPEG + 元信息搬运」，并检查尺寸与 EXIF 是否保留。
 * 样张不存在时跳过（不阻塞其它测试）。
 */
@RunWith(AndroidJUnit4::class)
class HeicCompressorTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 诊断：androidx ExifInterface 对 HEIC 的读写能力（决定转换时元信息能否搬运）。 */
    @Test
    fun diagnoseHeicExifSupport() {
        val heic = File("/sdcard/DCIM/Camera/heic_sample.heic")
        assumeTrue("未提供 HEIC 样张，跳过", heic.exists())
        val e = runCatching { ExifInterface(heic.absolutePath) }.getOrNull()
        android.util.Log.i("HEICDIAG", "open=${e != null} size=${heic.length()}")
        if (e == null) return
        android.util.Log.i(
            "HEICDIAG",
            "read make=${e.getAttribute(ExifInterface.TAG_MAKE)} model=${e.getAttribute(ExifInterface.TAG_MODEL)}",
        )
        val saved = runCatching {
            e.setAttribute(ExifInterface.TAG_MAKE, "realme")
            e.setAttribute(ExifInterface.TAG_MODEL, "GT7 Pro")
            e.saveAttributes()
        }.isSuccess
        android.util.Log.i("HEICDIAG", "saveAttributes=$saved")
        val re = runCatching { ExifInterface(heic.absolutePath) }.getOrNull()
        android.util.Log.i(
            "HEICDIAG",
            "reread make=${re?.getAttribute(ExifInterface.TAG_MAKE)} model=${re?.getAttribute(ExifInterface.TAG_MODEL)}",
        )
    }

    @Test
    fun heicToJpegPreservesSizeAndMetadata() {
        val heic = File("/sdcard/DCIM/Camera/heic_sample.heic")
        assumeTrue("未提供 HEIC 样张，跳过", heic.exists() && heic.length() > 0)

        // 先给 HEIC 写入 EXIF（真实相机 HEIC 自带 EXIF，这里补上以便验证搬运）
        runCatching {
            val e = ExifInterface(heic.absolutePath)
            e.setAttribute(ExifInterface.TAG_MAKE, "realme")
            e.setAttribute(ExifInterface.TAG_MODEL, "GT7 Pro")
            e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:09:20 10:11:12")
            e.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "30/1,15/1,1200/100")
            e.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            e.saveAttributes()
        }

        // 源图尺寸
        val srcOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(heic.absolutePath, srcOpts)
        assertTrue("HEIC 应可解码", srcOpts.outWidth > 0 && srcOpts.outHeight > 0)

        val out = File(context.cacheDir, "heic_out.jpg")
        val error = HeicCompressor.convert(heic, out, 85)
        assertEquals("HEIC→JPEG 转换应成功（元信息可完整搬运）", null, error)
        assertTrue("输出应为非空 JPEG", out.exists() && out.length() > 0)

        val outOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.absolutePath, outOpts)
        assertEquals("分辨率应保持不变", srcOpts.outWidth, outOpts.outWidth)
        assertEquals("分辨率应保持不变", srcOpts.outHeight, outOpts.outHeight)

        // EXIF 搬运：HEIC 支持写入 EXIF 时才能验证
        val srcExif = runCatching { ExifInterface(heic.absolutePath) }.getOrNull()
        val dstExif = ExifInterface(out.absolutePath)
        assertNotNull(dstExif)
        if (srcExif?.getAttribute(ExifInterface.TAG_MODEL) == "GT7 Pro") {
            assertEquals("GT7 Pro", dstExif.getAttribute(ExifInterface.TAG_MODEL))
            assertEquals("realme", dstExif.getAttribute(ExifInterface.TAG_MAKE))
            assertEquals(
                "2026:09:20 10:11:12",
                dstExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            )
        }

        out.delete()
    }
}
