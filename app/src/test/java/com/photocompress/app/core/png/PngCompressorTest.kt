package com.photocompress.app.core.png

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * PNG 位深闸门（[PngCompressor.probe]）：8 位与 16 位放行；1/2/4 位低色深、
 * 非法色彩类型、16 位配调色板各自给出独立原因。
 *
 * 只覆盖不依赖平台解码器的判定路径；16 位解码与 JPEG 输出见 androidTest。
 */
class PngCompressorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `8 位静态 PNG 继续放行`() {
        for (color in listOf(0, 2, 3, 4, 6)) {
            assertNull(
                "8 位 colorType=$color 应放行",
                probe(8, color),
            )
        }
    }

    @Test
    fun `16 位 PNG 进入压缩候选`() {
        for (color in listOf(0, 2, 4, 6)) {
            assertNull(
                "16 位 colorType=$color 应放行",
                probe(16, color),
            )
        }
    }

    @Test
    fun `低色深 PNG 给出低色深原因`() {
        for (depth in listOf(1, 2, 4)) {
            for (color in listOf(0, 3)) {
                val reason = probe(depth, color)
                assertNotNull("$depth 位 colorType=$color 应跳过", reason)
                assertTrue("原因应指明低色深：$reason", reason!!.contains("低色深"))
                assertTrue("原因不应再称仅支持 8 位：$reason", !reason.contains("仅支持 8 位"))
            }
        }
    }

    @Test
    fun `16 位配调色板被拒绝`() {
        val reason = probe(16, 3)
        assertNotNull("16 位调色板为非法组合，应跳过", reason)
        assertTrue("原因应指明组合非法：$reason", reason!!.contains("组合非法"))
    }

    @Test
    fun `非法色彩类型被拒绝`() {
        for (color in listOf(1, 5, 7)) {
            val reason = probe(8, color)
            assertNotNull("colorType=$color 不是合法 PNG 色彩类型，应跳过", reason)
            assertTrue("原因应指明色彩类型：$reason", reason!!.contains("色彩类型"))
        }
    }

    /** 写入一张合成 PNG 并返回 probe 的跳过原因（null 表示放行）。 */
    private fun probe(bitDepth: Int, color: Int): String? {
        val file = File(temp.root, "d$bitDepth-c$color.png")
        file.writeBytes(png(bitDepth, color))
        return PngCompressor.probe(file).skipReason
    }

    /** 合成 PNG：只有结构真实（长度、CRC、IHDR 字段），像素内容不参与 probe 判定。 */
    private fun png(bitDepth: Int, color: Int): ByteArray {
        val width = 4
        val height = 2
        val stride = (width * channels(color) * bitDepth / 8).coerceAtLeast(1)
        val raw = ByteArrayOutputStream()
        repeat(height) {
            raw.write(0)
            raw.write(ByteArray(stride))
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
