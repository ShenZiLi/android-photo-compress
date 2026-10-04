package com.photocompress.app.core.jpeg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JPEG 段级搬运与 MPF 索引重建的单元测试（implement.md 验证清单）。 */
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

    private fun exifSegment(payloadTail: ByteArray): ByteArray =
        seg(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + payloadTail)

    private fun xmpSegment(text: String): ByteArray =
        seg(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + text.toByteArray())

    private fun jfifSegment(): ByteArray = seg(0xE0, "JFIF\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(9))

    @Test
    fun `split 保留 SOI 之后的段并把熵数据放进 tail`() {
        val jpeg = soi + exifSegment(ByteArray(16) { 1 }) + sos + byteArrayOf(9, 9, 9) + eoi
        val split = JpegSegments.split(jpeg)
        assertEquals(1, split.segments.size)
        assertTrue(JpegSegments.isExif(split.segments[0]))
        // SOS 及其后原样保留
        assertEquals(4 + 3 + 2, split.tail.size)
    }

    @Test
    fun `transplantMetadata 用原图元信息段替换编码结果且保留编码段`() {
        val original = soi + jfifSegment() + exifSegment(ByteArray(24) { 7 }) + xmpSegment("<xmp>orig</xmp>") + sos + byteArrayOf(1, 2, 3) + eoi

        // 编码结果：只有 JFIF + DQT
        val encoded = soi + jfifSegment() +
            seg(0xDB, ByteArray(8) { 5 }) + sos + byteArrayOf(4, 5, 6, 7) + eoi

        val out = JpegSegments.transplantMetadata(encoded, original, null)
        val segments = JpegSegments.split(out).segments

        assertTrue("应保留原图 EXIF", segments.any { JpegSegments.isExif(it) })
        assertTrue("应保留原图 XMP", segments.any { JpegSegments.isXmp(it) })
        assertTrue("应保留编码结果的 DQT", segments.any { it.marker == 0xDB })
        // tail = SOS 段(4) + 熵数据(4) + EOI(2)
        assertEquals("取自编码结果的熵数据", 10, JpegSegments.split(out).tail.size)

        // EXIF 逐字节一致
        val origExif = JpegSegments.split(original).segments.first { JpegSegments.isExif(it) }.payload
        val outExif = segments.first { JpegSegments.isExif(it) }.payload
        assertArrayEquals(origExif, outExif)
    }

    @Test
    fun `transplantMetadata 的 xmpOverride 生效`() {
        val original = soi + exifSegment(ByteArray(8)) + xmpSegment("<old/>") + sos + eoi
        val encoded = soi + seg(0xDB, ByteArray(4)) + sos + eoi
        val out = JpegSegments.transplantMetadata(encoded, original, "<new/>")
        val xmp = JpegSegments.xmpTextOf(out)
        assertEquals("<new/>", xmp)
    }

    @Test
    fun `isJpeg 判定`() {
        assertTrue(JpegSegments.isJpeg(soi + ByteArray(4)))
        assertFalse(JpegSegments.isJpeg(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }

    // ------------------------------------------------------------ MPF

    /** 构造一个 2 图的 MP/Q 索引段（MM 字节序）。 */
    private fun buildMpfEntry(count: Int, sizes: List<Long>, offsets: List<Long>): ByteArray {
        val mpf = java.io.ByteArrayOutputStream()
        mpf.write("MPF\u0000".toByteArray(Charsets.ISO_8859_1))
        // TIFF 头：MM, 0x002A, 第一 IFD 偏移 = 8
        mpf.write(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08))
        // IFD: 2 条目
        val entryDataOffset = 8 + 2 + 3 * 12 + 4 // 50
        fun be32(v: Long) = byteArrayOf(
            ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
        )
        mpf.write(byteArrayOf(0x00, 0x03)) // 3 条目
        // 0xB000 MPFVersion
        mpf.write(byteArrayOf(0xB0.toByte(), 0x00, 0x00, 0x07, 0x00, 0x00, 0x00, 0x04)); mpf.write("0100".toByteArray())
        // 0xB001 NumberOfImages
        mpf.write(byteArrayOf(0xB0.toByte(), 0x01, 0x00, 0x04, 0x00, 0x00, 0x00, 0x01)); mpf.write(be32(count.toLong()))
        // 0xB002 MPEntry -> 指向 entryDataOffset
        mpf.write(byteArrayOf(0xB0.toByte(), 0x02, 0x00, 0x07, 0x00, 0x00, 0x00, (count * 16).toByte()))
        mpf.write(be32(entryDataOffset.toLong()))
        mpf.write(byteArrayOf(0x00, 0x00, 0x00, 0x00)) // next IFD = 0
        for (i in 0 until count) {
            mpf.write(be32(0x00030000L)) // attribute
            mpf.write(be32(sizes[i]))
            mpf.write(be32(offsets[i]))
            mpf.write(byteArrayOf(0, 0, 0, 0)) // dependants
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

        // 重新解析验证
        val text = String(updated, Charsets.ISO_8859_1)
        assertTrue(text.contains("MPF"))
        // 通过再次读取确认张数未变
        assertEquals(2, MpfRewriter.numberOfImages(updated))

        // 手动检查 MPEntry 数据区
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
        val got = JpegSegments.mpfPayloadOf(jpeg)
        assertNotNull(got)
        assertArrayEquals(payload, got)
    }
}
