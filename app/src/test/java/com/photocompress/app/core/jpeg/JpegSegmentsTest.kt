package com.photocompress.app.core.jpeg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JPEG 段级重组与 MPF 索引重建（implement.md 验证清单）。 */
class JpegSegmentsTest {

    private fun seg(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(), marker.toByte(),
            ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte(),
        ) + payload
    }

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02)

    private fun exifSegment(tail: ByteArray): ByteArray =
        seg(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tail)

    private fun xmpSegment(text: String): ByteArray =
        seg(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + text.toByteArray())

    private fun jfifSegment(): ByteArray = seg(0xE0, "JFIF\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(9))

    private fun iccSegment(): ByteArray =
        seg(0xE2, "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(20))

    private fun vendorSegment(): ByteArray = seg(0xE4, "QTI Debug\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(8))

    @Test
    fun `split 保留 SOI 之后的段并把熵数据放进 tail`() {
        val jpeg = soi + exifSegment(ByteArray(16) { 1 }) + sos + byteArrayOf(9, 9, 9) + eoi
        val split = JpegSegments.split(jpeg)
        assertEquals(1, split.segments.size)
        assertTrue(JpegSegments.isExif(split.segments[0]))
        assertEquals(4 + 3 + 2, split.tail.size)
    }

    @Test
    fun `rebuildWithMetadata 保留原图全部元信息段并替换 XMP`() {
        val original = soi + jfifSegment() + exifSegment(ByteArray(24) { 7 }) +
            xmpSegment("<old/>") + iccSegment() + vendorSegment() +
            seg(0xFE, "comment".toByteArray()) + sos + byteArrayOf(1, 2, 3) + eoi

        // 编码结果：自带 JFIF + DQT，图像数据应被采用
        val encoded = soi + jfifSegment() +
            seg(0xDB, ByteArray(8) { 5 }) + sos + byteArrayOf(4, 5, 6, 7) + eoi

        val out = JpegSegments.rebuildWithMetadata(encoded, original, "<new/>")
        val segs = JpegSegments.split(out).segments

        assertTrue("EXIF 应保留", segs.any { JpegSegments.isExif(it) })
        assertTrue("ICC_PROFILE 应保留（曾因丢段导致色彩/兼容问题）", segs.any { JpegSegments.isIcc(it) })
        assertTrue("厂商私有 APP4 应保留", segs.any { it.marker == 0xE4 })
        assertTrue("COM 段应保留", segs.any { it.marker == 0xFE })
        assertTrue("编码结果的 DQT 应被采用", segs.any { it.marker == 0xDB })
        assertEquals("XMP 应被替换", "<new/>", JpegSegments.xmpTextOf(out))
        assertEquals("熵数据取自编码结果", 10, JpegSegments.split(out).tail.size)

        // EXIF 逐字节一致
        assertArrayEquals(
            JpegSegments.split(original).segments.first { JpegSegments.isExif(it) }.payload,
            segs.first { JpegSegments.isExif(it) }.payload,
        )
        // 原图的 JFIF 只保留一份（不叠加编码器的）
        assertEquals(1, segs.count { JpegSegments.isJfif(it) })
    }

    @Test
    fun `rebuildWithMetadata 的 xmpOverride 为 null 时保留原 XMP`() {
        val original = soi + exifSegment(ByteArray(8)) + xmpSegment("<keep/>") + sos + eoi
        val encoded = soi + seg(0xDB, ByteArray(4)) + sos + eoi
        val out = JpegSegments.rebuildWithMetadata(encoded, original, null)
        assertEquals("<keep/>", JpegSegments.xmpTextOf(out))
    }

    @Test
    fun `rebuildWithMetadata 在原图无 XMP 时可写入新 XMP`() {
        val original = soi + exifSegment(ByteArray(8)) + sos + eoi
        val encoded = soi + seg(0xDB, ByteArray(4)) + sos + eoi
        val out = JpegSegments.rebuildWithMetadata(encoded, original, "<added/>")
        assertEquals("<added/>", JpegSegments.xmpTextOf(out))
    }

    @Test
    fun `isJpeg 判定`() {
        assertTrue(JpegSegments.isJpeg(soi + ByteArray(4)))
        assertFalse(JpegSegments.isJpeg(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }

    // ------------------------------------------------------------ MPF

    private fun buildMpfEntry(count: Int, sizes: List<Long>, offsets: List<Long>): ByteArray {
        val mpf = java.io.ByteArrayOutputStream()
        mpf.write("MPF\u0000".toByteArray(Charsets.ISO_8859_1))
        mpf.write(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08))
        val entryDataOffset = 8 + 2 + 3 * 12 + 4
        fun be32(v: Long) = byteArrayOf(
            ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
        )
        mpf.write(byteArrayOf(0x00, 0x03))
        mpf.write(byteArrayOf(0xB0.toByte(), 0x00, 0x00, 0x07, 0x00, 0x00, 0x00, 0x04)); mpf.write("0100".toByteArray())
        mpf.write(byteArrayOf(0xB0.toByte(), 0x01, 0x00, 0x04, 0x00, 0x00, 0x00, 0x01)); mpf.write(be32(count.toLong()))
        mpf.write(byteArrayOf(0xB0.toByte(), 0x02, 0x00, 0x07, 0x00, 0x00, 0x00, (count * 16).toByte()))
        mpf.write(be32(entryDataOffset.toLong()))
        mpf.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        for (i in 0 until count) {
            mpf.write(be32(0x00030000L))
            mpf.write(be32(sizes[i]))
            mpf.write(be32(offsets[i]))
            mpf.write(byteArrayOf(0, 0, 0, 0))
        }
        return mpf.toByteArray()
    }

    @Test
    fun `MpfRewriter 读取张数并重写 size 与 offset`() {
        val payload = buildMpfEntry(2, listOf(1000L, 500L), listOf(0L, 1000L))
        assertEquals(2, MpfRewriter.numberOfImages(payload))

        val updated = MpfRewriter.updateEntries(payload, longArrayOf(1234L, 567L), longArrayOf(0L, 1234L))
        assertNotNull(updated)
        assertEquals("改写为定长", payload.size, updated!!.size)
        assertEquals(2, MpfRewriter.numberOfImages(updated))

        val entryBase = 4 + 8 + 2 + 3 * 12 + 4
        fun be32at(off: Int): Long =
            ((updated[off].toLong() and 0xFF) shl 24) or ((updated[off + 1].toLong() and 0xFF) shl 16) or
                ((updated[off + 2].toLong() and 0xFF) shl 8) or (updated[off + 3].toLong() and 0xFF)
        assertEquals(1234L, be32at(entryBase + 4))
        assertEquals(0L, be32at(entryBase + 8))
        assertEquals(567L, be32at(entryBase + 20))
        assertEquals(1234L, be32at(entryBase + 24))
    }

    @Test
    fun `MpfRewriter 数量不匹配时拒绝改写`() {
        val payload = buildMpfEntry(2, listOf(1L, 2L), listOf(0L, 1L))
        assertEquals(null, MpfRewriter.updateEntries(payload, longArrayOf(1L), longArrayOf(0L)))
    }

    @Test
    fun `mpfPayloadOf 能取出 APP2 MPF 段`() {
        val payload = buildMpfEntry(1, listOf(10L), listOf(0L))
        val jpeg = soi + seg(0xE2, payload) + sos + eoi
        assertArrayEquals(payload, JpegSegments.mpfPayloadOf(jpeg))
    }
}
