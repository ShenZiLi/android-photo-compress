package com.photocompress.app.core.jpeg

/**
 * MPF（Multi-Picture Format, CIPA DC-007）索引重建。
 *
 * 实况照片的 APP2 MPF 段声明了「主图 + 增益图」两张图的大小与偏移；
 * 主图重编码后长度变化，必须同步重算，否则读 MPF 的应用会定位错误。
 * 这里只改写 MPEntry 中的 size / offset，其余字节原样保留。
 */
object MpfRewriter {

    private val PREFIX = "MPF\u0000".toByteArray(Charsets.ISO_8859_1)

    const val TAG_MP_ENTRY = 0xB002
    const val TAG_NUMBER_OF_IMAGES = 0xB001

    data class Entry(val attributes: Long, val size: Long, val offset: Long, val dependent1: Int, val dependent2: Int)

    fun entries(payload: ByteArray): List<Entry>? {
        val ctx = readContext(payload) ?: return null
        return List(ctx.numberOfImages) { index ->
            val at = ctx.mpEntryOffset + index * 16
            Entry(u32(payload, at, ctx.littleEndian).toLong() and 0xFFFFFFFFL,
                u32(payload, at + 4, ctx.littleEndian).toLong() and 0xFFFFFFFFL,
                u32(payload, at + 8, ctx.littleEndian).toLong() and 0xFFFFFFFFL,
                u16(payload, at + 12, ctx.littleEndian), u16(payload, at + 14, ctx.littleEndian))
        }
    }

    /** 读取 MPF 声明的图像张数；无法解析返回 -1。 */
    fun numberOfImages(payload: ByteArray): Int {
        val ctx = readContext(payload) ?: return -1
        return ctx.numberOfImages
    }

    /**
     * 改写 MPEntry 的 size / offset（数量必须与 NumberOfImages 一致）。
     * 返回新的 payload（含 "MPF\0" 前缀）；失败返回 null。
     *
     * [offsetBase] 是该 MPF 段 payload 在文件中的绝对偏移。CIPA DC-007 规定
     * Individual Image Data Offset 是**相对 MP Endian（TIFF 头）位置**的偏移，
     * 而 MP Endian 位于 payload 内 [PREFIX] 之后，故实际基准为 [offsetBase] + 4。
     * oplus 实况照片样张即按此约定书写（增益图 offset + 基准 = 增益图绝对起点，逐字节吻合）。
     * 传 0 表示存储的已是基准相对值。
     */
    fun updateEntries(
        payload: ByteArray,
        sizes: LongArray,
        offsets: LongArray,
        offsetBase: Long = 0L,
    ): ByteArray? {
        if (sizes.size != offsets.size) return null
        val ctx = readContext(payload) ?: return null
        if (ctx.numberOfImages != sizes.size) return null
        val out = payload.copyOf()

        val base = if (offsetBase > 0L) offsetBase + PREFIX.size else 0L
        val mpEntry = ctx.mpEntryOffset
        for (i in sizes.indices) {
            val entry = mpEntry + i * 16
            if (entry + 16 > out.size) return null
            if (sizes[i] !in 1L..0xFFFFFFFFL) return null
            val relative = if (i == 0 && offsets[i] == 0L) 0L else offsets[i] - base
            if (relative !in 0L..0xFFFFFFFFL) return null
            writeU32(out, entry + 4, sizes[i], ctx.littleEndian)
            // 首图的绝对位置是 0（规范特例），减基准后为负，统一按 0 记，
            // 与 oplus 原文件中的写法一致。
            writeU32(out, entry + 8, relative, ctx.littleEndian)
        }
        return out
    }

    private data class Context(
        val littleEndian: Boolean,
        val numberOfImages: Int,
        val mpEntryOffset: Int,
    )

    private fun readContext(payload: ByteArray): Context? {
        if (payload.size < 16 || !startWith(payload, PREFIX)) return null
        val base = PREFIX.size
        val little = when {
            payload[base] == 'I'.code.toByte() && payload[base + 1] == 'I'.code.toByte() -> true
            payload[base] == 'M'.code.toByte() && payload[base + 1] == 'M'.code.toByte() -> false
            else -> return null
        }
        if (u16(payload, base + 2, little) != 42) return null
        val firstIfd = u32(payload, base + 4, little).toLong() and 0xFFFFFFFFL
        if (firstIfd < 8 || firstIfd > payload.size - base - 2L) return null
        val ifd = base + firstIfd.toInt()
        val count = u16(payload, ifd, little)
        if (ifd + 2L + count * 12L + 4 > payload.size) return null
        var numberOfImages = -1
        var mpEntryOffset = -1
        var mpEntryLength = -1L
        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            if (entry + 12 > payload.size) return null
            when (u16(payload, entry, little)) {
                TAG_NUMBER_OF_IMAGES -> {
                    if (numberOfImages != -1 || u16(payload, entry + 2, little) != 4 ||
                        u32(payload, entry + 4, little) != 1) return null
                    val number = u32(payload, entry + 8, little)
                    if (number <= 0) return null
                    numberOfImages = number
                }
                TAG_MP_ENTRY -> {
                    if (mpEntryOffset != -1 || u16(payload, entry + 2, little) != 7) return null
                    val offset = u32(payload, entry + 8, little).toLong() and 0xFFFFFFFFL
                    if (offset < 8 || offset > payload.size - base) return null
                    mpEntryOffset = base + offset.toInt()
                    mpEntryLength = u32(payload, entry + 4, little).toLong() and 0xFFFFFFFFL
                }
            }
        }
        if (mpEntryOffset < 0 || numberOfImages <= 0) return null
        if (numberOfImages * 16L != mpEntryLength || mpEntryOffset + mpEntryLength > payload.size) return null
        return Context(little, numberOfImages, mpEntryOffset)
    }

    private fun startWith(a: ByteArray, prefix: ByteArray): Boolean {
        if (a.size < prefix.size) return false
        for (i in prefix.indices) if (a[i] != prefix[i]) return false
        return true
    }

    private fun u16(b: ByteArray, off: Int, little: Boolean): Int =
        if (little) (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        else ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, off: Int, little: Boolean): Int {
        val b0 = b[off].toInt() and 0xFF
        val b1 = b[off + 1].toInt() and 0xFF
        val b2 = b[off + 2].toInt() and 0xFF
        val b3 = b[off + 3].toInt() and 0xFF
        return if (little) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun writeU32(b: ByteArray, off: Int, value: Long, little: Boolean) {
        val v = value.toInt()
        if (little) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v ushr 8) and 0xFF).toByte()
            b[off + 2] = ((v ushr 16) and 0xFF).toByte()
            b[off + 3] = ((v ushr 24) and 0xFF).toByte()
        } else {
            b[off] = ((v ushr 24) and 0xFF).toByte()
            b[off + 1] = ((v ushr 16) and 0xFF).toByte()
            b[off + 2] = ((v ushr 8) and 0xFF).toByte()
            b[off + 3] = (v and 0xFF).toByte()
        }
    }
}
