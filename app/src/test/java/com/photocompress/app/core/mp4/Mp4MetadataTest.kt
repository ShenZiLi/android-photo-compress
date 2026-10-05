package com.photocompress.app.core.mp4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/** MP4 元数据搬运：udta 注入 + mvhd 时间同步 + stco 偏移修正。 */
class Mp4MetadataTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        val head = byteArrayOf(
            ((size ushr 24) and 0xFF).toByte(), ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(), (size and 0xFF).toByte(),
        ) + type.toByteArray(Charsets.US_ASCII)
        return head + payload
    }

    private fun be32(v: Int) = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    private fun udta(name: String): ByteArray =
        box("udta", box("\u00A9nam", name.toByteArray(Charsets.UTF_8)))

    /** mvhd：version 0 + flags + 创建时间 + 修改时间 + 其余填充。 */
    private fun mvhd(created: Int, modified: Int): ByteArray {
        val p = ByteArray(100)
        p[0] = 0
        be32(created).copyInto(p, 4)
        be32(modified).copyInto(p, 8)
        return box("mvhd", p)
    }

    private fun moovWithStco(chunkOffsets: List<Int>): ByteArray {
        val stcoPayload = ByteArray(8 + chunkOffsets.size * 4)
        be32(chunkOffsets.size).copyInto(stcoPayload, 4)
        chunkOffsets.forEachIndexed { i, v -> be32(v).copyInto(stcoPayload, 8 + i * 4) }
        val stbl = box("stbl", box("stco", stcoPayload))
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val trak = box("trak", mdia)
        return box("moov", mvhd(1, 2) + trak)
    }

    private fun write(file: File, boxes: List<ByteArray>) {
        file.outputStream().use { out -> boxes.forEach { out.write(it) } }
    }

    private fun readBoxes(file: File): List<Mp4Metadata.Box> =
        RandomAccessFile(file, "r").use { raf ->
            val out = ArrayList<Mp4Metadata.Box>()
            var i = 0L
            val len = raf.length()
            val head = ByteArray(8)
            while (i + 8 <= len) {
                raf.seek(i)
                raf.readFully(head)
                val size = ((head[0].toInt() and 0xFF shl 24) or (head[1].toInt() and 0xFF shl 16) or
                    (head[2].toInt() and 0xFF shl 8) or (head[3].toInt() and 0xFF)).toLong()
                out += Mp4Metadata.Box(String(head, 4, 4, Charsets.US_ASCII), i, size)
                i += size
            }
            out
        }

    private fun chunkOffsets(file: File): List<Int> {
        val bytes = file.readBytes()
        val idx = indexOf(bytes, "stco".toByteArray(Charsets.US_ASCII))
        require(idx > 0)
        val count = ((bytes[idx + 8].toInt() and 0xFF shl 24) or (bytes[idx + 9].toInt() and 0xFF shl 16) or
            (bytes[idx + 10].toInt() and 0xFF shl 8) or (bytes[idx + 11].toInt() and 0xFF))
        return (0 until count).map { i ->
            val p = idx + 12 + i * 4
            (bytes[p].toInt() and 0xFF shl 24) or (bytes[p + 1].toInt() and 0xFF shl 16) or
                (bytes[p + 2].toInt() and 0xFF shl 8) or (bytes[p + 3].toInt() and 0xFF)
        }
    }

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    private fun longAt(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF shl 24) or (bytes[off + 1].toInt() and 0xFF shl 16) or
            (bytes[off + 2].toInt() and 0xFF shl 8) or (bytes[off + 3].toInt() and 0xFF)

    @Test
    fun `注入 udta 并修正 chunk 偏移`() {
        // 目标：ftyp + moov(mvhd + trak/stco[1000,2000]) + mdat
        val target = temp.newFile("t.mp4")
        val moovBefore = moovWithStco(listOf(1000, 2000))
        val mdat = box("mdat", ByteArray(64))
        write(target, listOf(box("ftyp", "mp42".toByteArray()), moovBefore, mdat))
        val mdatBefore = readBoxes(target).first { it.type == "mdat" }.offset
        val moovSizeBefore = readBoxes(target).first { it.type == "moov" }.size
        assertEquals(listOf(1000, 2000), chunkOffsets(target))

        // 源：带 udta + 原始 mvhd 时间
        val source = temp.newFile("s.mp4")
        write(
            source,
            listOf(
                box("ftyp", "mp42".toByteArray()),
                box("moov", mvhd(1111, 2222) + udta("realme GT7 Pro")),
                box("mdat", ByteArray(32)),
            ),
        )

        assertTrue(Mp4Metadata.inject(target, source))

        val bytesAfter = target.readBytes()
        val boxesAfter = readBoxes(target)
        val moovAfter = boxesAfter.first { it.type == "moov" }
        assertTrue("moov 应变大（新增 udta）", moovAfter.size > moovSizeBefore)
        assertTrue("应包含源 udta", indexOf(bytesAfter, "realme GT7 Pro".toByteArray()) > 0)

        val delta = (moovAfter.size - moovSizeBefore).toInt()
        val mdatAfter = boxesAfter.first { it.type == "mdat" }.offset
        assertEquals("mdat 应后移 delta", mdatBefore + delta, mdatAfter)
        assertEquals("stco 应整体平移 delta", listOf(1000 + delta, 2000 + delta), chunkOffsets(target))

        // mvhd 时间被同步为源的值（moov 内第一个子框即 mvhd：8 字节 box 头 + 4 字节 version/flags）
        val moovPayload = bytesAfter.copyOfRange((moovAfter.offset + 8).toInt(), (moovAfter.offset + moovAfter.size).toInt())
        assertEquals(1111, longAt(moovPayload, 12))
        assertEquals(2222, longAt(moovPayload, 16))
    }

    @Test
    fun `源无可搬运元数据时不修改文件`() {
        val target = temp.newFile("t2.mp4")
        write(target, listOf(box("ftyp", "mp42".toByteArray()), moovWithStco(listOf(500)), box("mdat", ByteArray(16))))
        val source = temp.newFile("s2.mp4")
        write(source, listOf(box("ftyp", "mp42".toByteArray()), moovWithStco(listOf(500)), box("mdat", ByteArray(16))))

        // 源有 mvhd 时间，因此会同步时间但不应改变长度
        val sizeBefore = target.length()
        assertTrue(Mp4Metadata.inject(target, source))
        assertEquals(sizeBefore, target.length())
        assertEquals(listOf(500), chunkOffsets(target))
    }

    @Test
    fun `读取 mvhd 时间`() {
        val src = temp.newFile("s3.mp4")
        write(src, listOf(box("ftyp", "mp42".toByteArray()), box("moov", mvhd(1690000000, 1690000123)), box("mdat", ByteArray(8))))
        val times = Mp4Metadata.readMvhdTimes(src)
        assertEquals(1690000000L, times!![0])
        assertEquals(1690000123L, times[1])
    }

    @Test
    fun `产物自带同名 meta 时仍以源为准`() {
        // 回归：MediaMuxer 自己会写一个空的 moov/meta，早前实现见「同名已存在」就跳过，
        // 导致源里带相机信息的 meta 被那个空框顶掉（真机实测 447B → 118B）。
        val target = temp.newFile("t4.mp4")
        write(
            target,
            listOf(
                box("ftyp", "mp42".toByteArray()),
                box("moov", mvhd(1, 2) + box("meta", ByteArray(90) { 7 })),
                box("mdat", ByteArray(16)),
            ),
        )

        val source = temp.newFile("s4.mp4")
        write(
            source,
            listOf(
                box("ftyp", "mp42".toByteArray()),
                box("moov", mvhd(1, 2) + box("meta", "CAMERA-INFO".toByteArray())),
                box("mdat", ByteArray(16)),
            ),
        )

        assertTrue(Mp4Metadata.inject(target, source))
        assertTrue(
            "源的 meta 应覆盖产物自带的同名 meta",
            indexOf(target.readBytes(), "CAMERA-INFO".toByteArray()) > 0,
        )
    }

    @Test
    fun `搬运 udta meta 之外的厂商私有框`() {
        // 回归：厂商会在 moov 下放私有框（realme 实测有 titl），
        // 早前只挑 udta/meta，这类框会直接丢失。
        val target = temp.newFile("t5.mp4")
        write(target, listOf(box("ftyp", "mp42".toByteArray()), moovWithStco(listOf(500)), box("mdat", ByteArray(16))))

        val source = temp.newFile("s5.mp4")
        write(
            source,
            listOf(
                box("ftyp", "mp42".toByteArray()),
                box("moov", mvhd(1, 2) + box("titl", "Oplus_0".toByteArray())),
                box("mdat", ByteArray(16)),
            ),
        )

        assertTrue(Mp4Metadata.inject(target, source))
        assertTrue(
            "厂商私有框 titl 应被搬运",
            indexOf(target.readBytes(), "Oplus_0".toByteArray()) > 0,
        )
    }

    @Test
    fun `产物自有的结构框不被源覆盖`() {
        // trak / mvhd 由产物提供，不能拿源的换掉（否则轨道结构与时长就错了）
        val target = temp.newFile("t6.mp4")
        write(target, listOf(box("ftyp", "mp42".toByteArray()), moovWithStco(listOf(500)), box("mdat", ByteArray(16))))

        val source = temp.newFile("s6.mp4")
        write(
            source,
            listOf(
                box("ftyp", "mp42".toByteArray()),
                box("moov", mvhd(1111, 2222) + box("trak", "SOURCE-TRAK".toByteArray())),
                box("mdat", ByteArray(16)),
            ),
        )

        val targetSizeBefore = target.length()
        assertTrue(Mp4Metadata.inject(target, source))
        assertEquals("源的 trak 不应被搬进来", -1, indexOf(target.readBytes(), "SOURCE-TRAK".toByteArray()))
        assertEquals("除 mvhd 时间外长度不变", targetSizeBefore, target.length())
    }
}
