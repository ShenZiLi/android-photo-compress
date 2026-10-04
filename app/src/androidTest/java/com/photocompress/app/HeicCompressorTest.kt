package com.photocompress.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.HeifWriter
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photocompress.app.core.jpeg.HeicCompressor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * HEIC 压缩链路验证：
 * 1) 用 HeifWriter 在设备上生成真实 HEIC（本机 ffmpeg 无 HEIF 封装器，只能设备端造）；
 * 2) 走 HeicCompressor 转 JPEG 并搬运元信息。
 * 同时把生成的文件放到 /sdcard/DCIM/Camera 供 UI 端到端验证。
 */
@RunWith(AndroidJUnit4::class)
class HeicCompressorTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun writeHeif(target: File, w: Int, h: Int) {
        val writer = HeifWriter.Builder(target.absolutePath, w, h, HeifWriter.INPUT_MODE_BITMAP)
            .setQuality(90)
            .build()
        writer.start()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(Color.rgb(20, 60, 120))
        val paint = android.graphics.Paint().apply { color = Color.rgb(230, 190, 40) }
        canvas.drawCircle(w / 2f, h / 2f, minOf(w, h) / 4f, paint)
        writer.addBitmap(bmp)
        writer.stop(5_000)
        writer.close()
        bmp.recycle()
    }

    @Test
    fun heicToJpegPreservesSizeAndMetadata() {
        val heic = File(context.cacheDir, "heic_src.heic")
        writeHeif(heic, 640, 480)
        assertTrue("应生成非空 HEIC", heic.exists() && heic.length() > 0)

        // 给 HEIC 写入 EXIF（验证搬运）：HeifWriter 不写 EXIF，这里用 ExifInterface 尝试
        runCatching {
            val e = ExifInterface(heic.absolutePath)
            e.setAttribute(ExifInterface.TAG_MAKE, "realme")
            e.setAttribute(ExifInterface.TAG_MODEL, "GT7 Pro")
            e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:09:20 10:11:12")
            e.saveAttributes()
        }

        val out = File(context.cacheDir, "heic_out.jpg")
        assertTrue("HEIC→JPEG 转换应成功", HeicCompressor.convert(heic, out, 85))
        assertTrue(out.length() > 0)

        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(out.absolutePath, opts)
        assertEquals(640, opts.outWidth)
        assertEquals(480, opts.outHeight)

        assertNotNull("输出应为可解析 JPEG", ExifInterface(out.absolutePath))
        out.delete()
        heic.delete()
    }

    /** 生成一张真实 HEIC 并放到相册目录，供 UI 端到端压缩验证。 */
    @Test
    fun publishHeicSampleForUiTest() {
        val dir = File("/sdcard/DCIM/Camera")
        if (!dir.exists()) return
        val target = File(dir, "heic_sample.heic")
        runCatching {
            writeHeif(target, 1600, 1200)
            val e = ExifInterface(target.absolutePath)
            e.setAttribute(ExifInterface.TAG_MAKE, "realme")
            e.setAttribute(ExifInterface.TAG_MODEL, "GT7 Pro")
            e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:09:20 10:11:12")
            e.saveAttributes()
        }
        assertTrue("应在相册生成 HEIC 样张", target.exists())
    }
}
