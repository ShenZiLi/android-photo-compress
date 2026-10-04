package com.photocompress.app.core.xmp

import java.io.File
import java.io.RandomAccessFile

/**
 * MP4 内的 XMP 标记（D5 / F7）。
 *
 * JPEG 走 APP1 XMP；MP4 没有 APP 段，按 Adobe 规范使用顶层的 `uuid` box：
 * `[size(4)][type='uuid'(4)][UUID(16)][XMP packet]`。
 * 追加在文件末尾，不移动 `moov` / `mdat`，因此不会破坏 `stco/co64` 的绝对偏移。
 */
object Mp4XmpMarker {

    private val XMP_UUID = byteArrayOf(
        0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(),
        0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
        0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(),
        0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte(),
    )

    /** 全文尾部扫描窗口：标记由本应用追加在末尾。 */
    private const val TAIL_SCAN = 512 * 1024

    /** 把自有 XMP 标记追加为顶层 uuid box。返回是否成功。 */
    fun write(file: File, marker: PcXmp.Marker, existingXmp: String? = null): Boolean = runCatching {
        val packet = PcXmp.injectAttributes(existingXmp, marker).toByteArray(Charsets.UTF_8)
        val boxSize = 4 + 4 + XMP_UUID.size + packet.size
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(intToBytes(boxSize))
            raf.write("uuid".toByteArray(Charsets.US_ASCII))
            raf.write(XMP_UUID)
            raf.write(packet)
            raf.fd.sync()
        }
        true
    }.getOrDefault(false)

    /** 读取文件末尾的自有标记；不存在返回 null。 */
    fun read(file: File): PcXmp.Marker? = runCatching {
        val length = file.length()
        if (length < 32) return null
        val window = minOf(TAIL_SCAN.toLong(), length).toInt()
        val start = length - window
        val buf = ByteArray(window)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            raf.readFully(buf)
        }
        val uuidAt = indexOf(buf, XMP_UUID)
        if (uuidAt < 8) return null
        // box 头在 UUID 之前 8 字节
        val size = beInt(buf, uuidAt - 8)
        val type = String(buf, uuidAt - 4, 4, Charsets.US_ASCII)
        if (type != "uuid" || size <= 20) return null
        val textEnd = minOf(uuidAt + XMP_UUID.size + (size - 20), buf.size)
        val xmp = String(buf, uuidAt + XMP_UUID.size, textEnd - uuidAt - XMP_UUID.size, Charsets.UTF_8)
        PcXmp.read(xmp)
    }.getOrNull()

    private fun intToBytes(v: Int) = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
