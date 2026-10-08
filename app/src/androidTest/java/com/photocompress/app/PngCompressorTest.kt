package com.photocompress.app

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photocompress.app.core.jpeg.JpegSegments
import com.photocompress.app.core.png.PngCompressor
import com.photocompress.app.core.xmp.PcXmp
import com.photocompress.app.data.media.QualityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * 16 位 PNG 转 JPEG 的端到端验收：设备解码能力、输出格式、尺寸与唯一标记。
 *
 * 输入为合成 16 位 PNG（样本值按 ×257 满量程展开，与 8 位等价），
 * 因此不依赖任何私有样张，任何设备都可执行。
 */
@RunWith(AndroidJUnit4::class)
class PngCompressorTest {

    private val width = 8
    private val height = 8

    @Test
    fun `16 位 PNG 转出同尺寸 JPEG 并带唯一标记`() {
        for (color in listOf(0, 2, 4, 6)) {
            val marker = PcXmp.Marker(UUID.randomUUID().toString(), "png16-c$color", 1L)
            val encoded = PngCompressor.compress(source(16, color, alpha = 255), QualityTier.BALANCED, marker) {}
            assertEquals("colorType=$color 应记录 16 位来源", 16, encoded.bitDepth)
            assertTrue("输出应为 JPEG", encoded.bytes[0] == 0xFF.toByte() && encoded.bytes[1] == 0xD8.toByte())
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size, options)
            assertEquals("colorType=$color 宽度", width, options.outWidth)
            assertEquals("colorType=$color 高度", height, options.outHeight)
            assertEquals("colorType=$color 唯一标记", marker, PcXmp.read(JpegSegments.xmpTextOf(encoded.bytes)))
        }
    }

    @Test
    fun `16 位非满量程透明度仍按透明跳过`() {
        val thrown = runCatching {
            PngCompressor.compress(source(16, 6, alpha = 128), QualityTier.BALANCED, marker()) {}
        }.exceptionOrNull()
        assertTrue("应抛 IllegalArgumentException，实际 $thrown", thrown is IllegalArgumentException)
        assertTrue("原因应指明透明：${thrown?.message}", thrown?.message?.contains("透明") == true)
    }

    @Test
    fun `16 位高位深不再被位深闸门拒绝`() {
        val skipped = runCatching {
            PngCompressor.compress(source(16, 2, alpha = 255), QualityTier.BALANCED, marker()) {}
        }.exceptionOrNull()
        assertTrue("16 位不应再被位深闸门拒绝：${skipped?.message}", skipped?.message?.contains("8 位") != true)
    }

    @Test
    fun `8 位 PNG 行为不变`() {
        val marker = marker()
        val encoded = PngCompressor.compress(source(8, 6, alpha = 255), QualityTier.BALANCED, marker) {}
        assertEquals(8, encoded.bitDepth)
        assertEquals(marker, PcXmp.read(JpegSegments.xmpTextOf(encoded.bytes)))
    }

    private fun marker() = PcXmp.Marker(UUID.randomUUID().toString(), "png-test", 1L)

    /** 合成 PNG：样本值 ×257 展开到 16 位，保证回到 8 位时与原始值等价。 */
    private fun source(bitDepth: Int, color: Int, alpha: Int): ByteArray {
        val channels = channels(color)
        val alphaAt = when (color) { 4 -> 1; 6 -> 3; else -> -1 }
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                for (c in 0 until channels) {
                    val value = if (c == alphaAt) alpha else (x * 24 + y * 8 + c * 16) and 0xFF
                    if (bitDepth == 16) { raw.write(value); raw.write(value) } else raw.write(value)
                }
            }
        }
        val deflated = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out).use { it.write(raw.toByteArray()) }
        }.toByteArray()
        val ihdr = int(width) + int(height) + byteArrayOf(bitDepth.toByte(), color.toByte(), 0, 0, 0)
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            write(chunk("IHDR", ihdr))
            write(chunk("IDAT", deflated))
            write(chunk("IEND", ByteArray(0)))
        }.toByteArray()
    }

    private fun channels(color: Int): Int = when (color) { 0, 3 -> 1; 2 -> 3; 4 -> 2; else -> 4 }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(name); update(data) }.value.toInt()
        return int(data.size) + name + data + int(crc)
    }

    private fun int(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )
}
