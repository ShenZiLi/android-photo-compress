package com.photocompress.app.core.mp4

import java.io.File
import java.io.RandomAccessFile

/**
 * MP4 元数据搬运。
 *
 * MediaMuxer 转码后只保留编码必需的结构，源文件的 `moov/udta`（相机厂商、机型、
 * GPS、拍摄时间等）会丢失——这正是"视频压缩后相机信息消失"的原因。
 *
 * 这里把源文件的 `udta` / `meta` 子框搬到转码结果的 `moov` 内，并同步 `mvhd`
 * 的创建/修改时间。`moov` 位于 `mdat` 之前时，`moov` 变大会让 `mdat` 后移，
 * 因此必须同步修正 `stco` / `co64` 中的 chunk 偏移。
 */
object Mp4Metadata {

    private val CONTAINER_TYPES = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "udta", "mvex", "moof", "traf")

    data class Box(val type: String, val offset: Long, val size: Long)

    /** 源文件中需要搬运的元数据框原始字节（udta / meta）。 */
    fun extractMetaBoxes(source: File): List<Box> = runCatching {
        RandomAccessFile(source, "r").use { raf ->
            val moov = topLevelBoxes(raf).firstOrNull { it.type == "moov" } ?: return emptyList()
            childBoxes(raf, moov).filter { it.type == "udta" || it.type == "meta" }
        }
    }.getOrDefault(emptyList())

    /** 读取源文件 mvhd 的 [创建时间, 修改时间]（MP4 epoch 秒）。 */
    fun readMvhdTimes(source: File): LongArray? = runCatching {
        RandomAccessFile(source, "r").use { raf ->
            val moov = topLevelBoxes(raf).firstOrNull { it.type == "moov" } ?: return null
            val mvhd = childBoxes(raf, moov).firstOrNull { it.type == "mvhd" } ?: return null
            val head = readBytes(raf, mvhd.offset, minOf(mvhd.size, 32L))
            val version = head[8].toInt() and 0xFF
            if (version == 1) {
                longArrayOf(beLong(head, 12), beLong(head, 20))
            } else {
                longArrayOf(beInt(head, 12).toLong() and 0xFFFFFFFFL, beInt(head, 16).toLong() and 0xFFFFFFFFL)
            }
        }
    }.getOrNull()

    /**
     * 把 [metaBoxes]（来自源文件）合入 [target] 的 moov：
     * 同类型子框就地替换，否则追加；随后修正 chunk 偏移与 mvhd 时间。
     * [target] 必须是调用方自己创建的临时文件。
     */
    fun inject(target: File, source: File): Boolean = runCatching {
        val metaBoxes = extractMetaBoxes(source)
        val times = readMvhdTimes(source)
        if (metaBoxes.isEmpty() && times == null) return true

        val raf = RandomAccessFile(target, "r")
        val top: List<Box>
        val moov: Box
        val mdat: Box?
        try {
            top = topLevelBoxes(raf)
            moov = top.firstOrNull { it.type == "moov" } ?: return false
            mdat = top.firstOrNull { it.type == "mdat" }
        } finally {
            raf.close()
        }

        val moovBytes = RandomAccessFile(target, "r").use { readBytes(it, moov.offset, moov.size) }
        val children = childBoxesInMemory(moovBytes, 8, moovBytes.size)

        // 同类型子框就地替换（udta 是相机信息的权威来源，一律用源替换）；否则追加
        val replacements = LinkedHashMap<String, ByteArray>()
        RandomAccessFile(source, "r").use { src ->
            for (b in metaBoxes) {
                if (replacements.containsKey(b.type)) continue
                val hasSame = children.any { it.type == b.type }
                if (!hasSame || b.type == "udta") replacements[b.type] = readBytes(src, b.offset, b.size)
            }
        }

        val newChildren = java.io.ByteArrayOutputStream()
        for (c in children) {
            val rep = replacements.remove(c.type)
            if (rep != null) {
                newChildren.write(rep)
            } else {
                newChildren.write(moovBytes, c.offset.toInt(), c.size.toInt())
            }
        }
        for (bytes in replacements.values) newChildren.write(bytes)

        val childrenBytes = newChildren.toByteArray()
        val newMoov = ByteArray(8 + childrenBytes.size)
        writeU32(newMoov, 0, newMoov.size.toLong())
        "moov".toByteArray(Charsets.US_ASCII).copyInto(newMoov, 4)
        childrenBytes.copyInto(newMoov, 8)
        if (times != null) patchMvhdTime(newMoov, times)

        val delta = (newMoov.size - moov.size).toLong()
        if (delta != 0L && mdat != null && moov.offset < mdat.offset) {
            shiftChunkOffsets(newMoov, 8, newMoov.size, delta)
        }

        val tmp = File(target.parentFile, target.name + ".pcmeta")
        RandomAccessFile(tmp, "rw").use { out ->
            RandomAccessFile(target, "r").use { input ->
                streamCopy(input, 0, moov.offset, out)
                out.write(newMoov)
                streamCopy(input, moov.offset + moov.size, target.length(), out)
            }
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------ internals

    private fun topLevelBoxes(raf: RandomAccessFile): List<Box> = boxesInRange(raf, 0, raf.length())

    private fun childBoxes(raf: RandomAccessFile, parent: Box): List<Box> =
        boxesInRange(raf, parent.offset + 8, parent.offset + parent.size)

    private fun boxesInRange(raf: RandomAccessFile, start: Long, end: Long): List<Box> {
        val out = ArrayList<Box>()
        var i = start
        val head = ByteArray(16)
        while (i + 8 <= end) {
            raf.seek(i)
            if (raf.read(head, 0, 8) < 8) break
            var size = beInt(head, 0).toLong() and 0xFFFFFFFFL
            val type = String(head, 4, 4, Charsets.US_ASCII)
            if (!type.all { it.code in 32..126 }) break
            if (size == 1L) {
                if (raf.read(head, 8, 8) < 8) break
                size = beLong(head, 8)
            } else if (size == 0L) {
                size = end - i
            }
            if (size < 8 || i + size > end) break
            out += Box(type, i, size)
            i += size
        }
        return out
    }

    private fun childBoxesInMemory(buf: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var i = start
        while (i + 8 <= end) {
            val size = beInt(buf, i).toLong() and 0xFFFFFFFFL
            val type = String(buf, i + 4, 4, Charsets.US_ASCII)
            if (size < 8 || i + size > end) break
            out += Box(type, i.toLong(), size)
            i += size.toInt()
        }
        return out
    }

    /** moov 内所有 stco / co64 的条目整体平移 [delta]。 */
    private fun shiftChunkOffsets(buf: ByteArray, start: Int, end: Int, delta: Long) {
        var i = start
        while (i + 8 <= end) {
            val size = beInt(buf, i).toLong() and 0xFFFFFFFFL
            val type = String(buf, i + 4, 4, Charsets.US_ASCII)
            if (size < 8 || i + size > end) return
            val bodyStart = i + 8
            val bodyEnd = (i + size).toInt()
            when (type) {
                "stco" -> {
                    val count = beInt(buf, bodyStart + 4)
                    var p = bodyStart + 8
                    for (k in 0 until count) {
                        if (p + 4 > bodyEnd) break
                        writeU32(buf, p, (beInt(buf, p).toLong() and 0xFFFFFFFFL) + delta)
                        p += 4
                    }
                }
                "co64" -> {
                    val count = beInt(buf, bodyStart + 4)
                    var p = bodyStart + 8
                    for (k in 0 until count) {
                        if (p + 8 > bodyEnd) break
                        writeU64(buf, p, beLong(buf, p) + delta)
                        p += 8
                    }
                }
                in CONTAINER_TYPES -> shiftChunkOffsets(buf, bodyStart, bodyEnd, delta)
            }
            i += size.toInt()
        }
    }

    private fun patchMvhdTime(moov: ByteArray, times: LongArray) {
        val children = childBoxesInMemory(moov, 8, moov.size)
        val mvhd = children.firstOrNull { it.type == "mvhd" } ?: return
        val base = mvhd.offset.toInt()
        val version = moov[base + 8].toInt() and 0xFF
        if (version == 1) {
            writeU64(moov, base + 12, times[0])
            writeU64(moov, base + 20, times[1])
        } else {
            if (times[0] > 0xFFFFFFFFL || times[1] > 0xFFFFFFFFL) return
            writeU32(moov, base + 12, times[0])
            writeU32(moov, base + 16, times[1])
        }
    }

    private fun readBytes(raf: RandomAccessFile, offset: Long, size: Long): ByteArray {
        val out = ByteArray(size.toInt())
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    private fun streamCopy(input: RandomAccessFile, from: Long, to: Long, out: RandomAccessFile) {
        input.seek(from)
        val buf = ByteArray(1 shl 16)
        var remaining = to - from
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun beLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun writeU32(b: ByteArray, off: Int, value: Long) {
        val v = value.toInt()
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    private fun writeU64(b: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) b[off + i] = ((value ushr (56 - i * 8)) and 0xFF).toByte()
    }
}
