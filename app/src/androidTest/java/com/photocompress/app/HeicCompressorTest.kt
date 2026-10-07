package com.photocompress.app

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photocompress.app.core.heif.HeicCompressor
import com.photocompress.app.core.xmp.PcXmp
import com.photocompress.app.data.media.QualityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 只读明确提供的私有 HEIC 副本，编码输出只写临时文件。 */
@RunWith(AndroidJUnit4::class)
class HeicCompressorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val source get() = File(context.cacheDir, "heic-safety-source.heic")

    @Test fun diagnoseHeicExifSupport() {
        assumeTrue("未提供私有 HEIC 样张，跳过", source.isFile)
        val exif = ExifInterface(source.path)
        assertTrue(exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) >= 0)
    }

    @Test fun heicPreservesSizeAndMetadata() {
        assumeTrue("未提供私有 HEIC 样张，跳过", source.isFile)
        val before = source.readBytes()
        val encoded = File.createTempFile("heic-encoded-", ".heic", context.cacheDir)
        val out = File.createTempFile("heic-result-", ".heic", context.cacheDir)
        try {
            val marker = PcXmp.Marker(UUID.randomUUID().toString(), "native-heic", 1L)
            HeicCompressor.compress(source, encoded, out, QualityTier.BALANCED, marker) {}
            val src = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val dst = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, src); BitmapFactory.decodeFile(out.path, dst)
            assertEquals(src.outWidth, dst.outWidth); assertEquals(src.outHeight, dst.outHeight)
            assertEquals(marker, HeicCompressor.markerOf(out))
            val a = ExifInterface(source.path); val b = ExifInterface(out.path)
            for (tag in listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_ORIENTATION)) {
                assertEquals(a.getAttribute(tag), b.getAttribute(tag))
            }
            assertTrue(source.readBytes().contentEquals(before))
        } finally { encoded.delete(); out.delete() }
    }
}
