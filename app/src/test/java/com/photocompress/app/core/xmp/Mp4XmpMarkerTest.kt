package com.photocompress.app.core.xmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** MP4 内 XMP 标记（顶层 uuid box）的读写往返（D5 / F7 / AC5）。 */
class Mp4XmpMarkerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val marker = PcXmp.Marker("video-uuid-9", "0.1.0", 1_700_000_000_000L)

    /** 造一个最小的“类 MP4”字节流：ftyp + mdat。 */
    private fun fakeMp4(): java.io.File {
        val f = temp.newFile("v.mp4")
        val ftyp = "ftypmp42".toByteArray(Charsets.US_ASCII)
        val head = byteArrayOf(0, 0, 0, 16) + ftyp
        val mdat = byteArrayOf(0, 0, 0, 8) + "mdat".toByteArray(Charsets.US_ASCII)
        f.writeBytes(head + mdat)
        return f
    }

    @Test
    fun `写入后能读回同一编号`() {
        val f = fakeMp4()
        val originalSize = f.length()
        assertNull("未写入前应为 null", Mp4XmpMarker.read(f))

        assertTrue(Mp4XmpMarker.write(f, marker))
        assertTrue("标记应追加在末尾，不移动已有 box", f.length() > originalSize)

        val read = Mp4XmpMarker.read(f)
        assertEquals("video-uuid-9", read?.id)
        assertEquals("0.1.0", read?.version)
    }

    @Test
    fun `追加的 box 结构符合 uuid box 规范`() {
        val f = fakeMp4()
        Mp4XmpMarker.write(f, marker)
        val bytes = f.readBytes()
        val uuidAt = bytes.indexOfSub("uuid".toByteArray(Charsets.US_ASCII))
        assertTrue(uuidAt > 0)
        val size = ((bytes[uuidAt - 4].toInt() and 0xFF) shl 24) or
            ((bytes[uuidAt - 3].toInt() and 0xFF) shl 16) or
            ((bytes[uuidAt - 2].toInt() and 0xFF) shl 8) or
            (bytes[uuidAt - 1].toInt() and 0xFF)
        assertEquals("box size 应等于文件尾部 box 的长度", f.length() - (uuidAt - 4), size.toLong())
    }

    @Test
    fun `无标记的文件读取返回 null`() {
        assertNull(Mp4XmpMarker.read(fakeMp4()))
    }

    private fun ByteArray.indexOfSub(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
