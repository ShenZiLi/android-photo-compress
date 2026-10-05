package com.photocompress.app.core.livephoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 实况照片 MotionPhoto 区段的切分与 XMP 数字就地改写。
 *
 * 背景：oplus/realme 的 MotionPhoto 区段 = 内嵌主视频 MP4 + 厂商私有尾块，
 * 二者只能靠 `OpCamera:VideoLength` 区分（该值实为字节长度，不是时长）。
 */
class LivePhotoContainerTest {

    /** 构造一个自洽的普通 MP4 box 链（ftyp + moov + mdat），用于充当「可重编码的主视频」。 */
    private fun fakeMp4(extra: Int = 0): ByteArray {
        fun box(type: String, payload: Int): ByteArray {
            val size = 8 + payload
            val b = ByteArray(size)
            b[0] = ((size ushr 24) and 0xFF).toByte()
            b[1] = ((size ushr 16) and 0xFF).toByte()
            b[2] = ((size ushr 8) and 0xFF).toByte()
            b[3] = (size and 0xFF).toByte()
            type.toByteArray(Charsets.ISO_8859_1).copyInto(b, 4)
            return b
        }
        return box("ftyp", 16) + box("moov", 32) + box("mdat", 64 + extra)
    }

    @Test
    fun splitMotion_separatesVideoAndVendorTail() {
        val video = fakeMp4()
        val tail = ByteArray(37) { (it + 1).toByte() }
        val motion = video + tail

        val split = LivePhotoContainer.splitMotion(motion, video.size.toLong())
        assertNotNull(split)
        assertArrayEquals(video, split!!.first)
        assertArrayEquals(tail, split.second)
    }

    @Test
    fun splitMotion_rejectsOutOfRangeLength() {
        val motion = fakeMp4() + ByteArray(8)
        assertNull(LivePhotoContainer.splitMotion(motion, 0L))
        assertNull(LivePhotoContainer.splitMotion(motion, -1L))
        assertNull(LivePhotoContainer.splitMotion(motion, motion.size.toLong()))
        assertNull(LivePhotoContainer.splitMotion(motion, motion.size.toLong() + 100))
    }

    @Test
    fun splitMotion_rejectsPrefixThatIsNotSelfContainedMp4() {
        val motion = fakeMp4() + ByteArray(16)
        // 前缀截在 mdat 中间：box 链耗尽不了整个前缀，不能安全重编码
        val truncated = motion.size.toLong() - 10
        assertNull(LivePhotoContainer.splitMotion(motion, truncated))
    }

    @Test
    fun rewriteVideoLength_replacesDigitsInPlace() {
        val xmp = """<rdf:Description OpCamera:VideoLength="5116097" OpCamera:MotionPhotoOwner="oplus"/>"""
        val out = LivePhotoContainer.rewriteVideoLength(xmp, 1_999_999L)
        assertEquals(
            """<rdf:Description OpCamera:VideoLength="1999999" OpCamera:MotionPhotoOwner="oplus"/>""",
            out,
        )
    }

    @Test
    fun rewriteVideoLength_leavesXmpUntouchedWhenAttributeMissing() {
        val xmp = """<rdf:Description OpCamera:MotionPhotoOwner="oplus"/>"""
        assertEquals(xmp, LivePhotoContainer.rewriteVideoLength(xmp, 123L))
    }
}
