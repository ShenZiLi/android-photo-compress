package com.photocompress.app.data.media

import java.io.File
import java.io.RandomAccessFile

/**
 * 实况照片（Google Motion Photo / oplus OLivePhoto）探测与容器解析。
 *
 * XMP 位于 JPEG 头部区段，这里只读前 256KB；内嵌 MP4 位于文件末尾，
 * 因此偏移 = 文件长度 − MotionPhoto 长度 − Padding（v2 的 Container:Directory 语义）。
 */
object LivePhotoDetector {

    private const val HEADER_SCAN = 256 * 1024

    data class LiveInfo(
        val motionPhotoOffset: Long,
        val motionPhotoLength: Long,
        val gainMapLength: Long,
        val primaryLengthHint: Long,
        val isOplus: Boolean,
        val videoDurationUs: Long,
    )

    data class XmpItem(
        val mime: String,
        val semantic: String,
        val length: Long,
        val padding: Long,
    )

    fun detect(file: File): LiveInfo? {
        val header = readHeader(file) ?: return null
        return detectFromHeader(header, file.length(), file)
    }

    /** 已读入头部字节时复用，避免同一文件读两遍。 */
    fun detectFromHeader(header: ByteArray, fileLength: Long, file: File? = null): LiveInfo? {
        if (fileLength <= 0) return null
        val text = String(header, Charsets.ISO_8859_1)

        val looksLive = text.contains("MotionPhoto") || text.contains("Container:Directory")
        if (!looksLive) return null

        val items = parseContainerItems(text)
        if (items.isNotEmpty()) {
            val motion = items.firstOrNull { it.semantic == "MotionPhoto" }
            val gain = items.firstOrNull { it.semantic == "GainMap" }
            val primary = items.firstOrNull { it.semantic == "Primary" }
            if (motion != null && motion.length in 1 until fileLength) {
                val offset = fileLength - motion.length - motion.padding
                if (offset > 0) {
                    return LiveInfo(
                        motionPhotoOffset = offset,
                        motionPhotoLength = motion.length,
                        gainMapLength = gain?.length ?: 0L,
                        primaryLengthHint = primary?.length ?: 0L,
                        isOplus = text.contains("oplus", ignoreCase = true),
                        videoDurationUs = extractVideoLengthUs(text),
                    )
                }
            }
        }

        // v1 兜底：无 Container:Directory，直接在文件中定位 MP4 的 ftyp box。
        val offset = file?.let { findMp4Start(it) } ?: return null
        return LiveInfo(
            motionPhotoOffset = offset,
            motionPhotoLength = fileLength - offset,
            gainMapLength = 0L,
            primaryLengthHint = 0L,
            isOplus = text.contains("oplus", ignoreCase = true),
            videoDurationUs = extractVideoLengthUs(text),
        )
    }

    /** 读取 JPEG 头部字节（用于实况结构解析与自有 XMP 标记读取）。 */
    fun readHeaderBytes(file: File): ByteArray? = readHeader(file)

    fun parseContainerItems(text: String): List<XmpItem> {
        val tagRegex = Regex("<Container:Item\\b([^>]*)/?>")
        val attrRegex = Regex("""([A-Za-z]+:[A-Za-z]+)="([^"]*)"""")
        return tagRegex.findAll(text).mapNotNull { m ->
            val attrs = attrRegex.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            val semantic = attrs["Item:Semantic"] ?: return@mapNotNull null
            val mime = attrs["Item:Mime"] ?: ""
            XmpItem(
                mime = mime,
                semantic = semantic,
                length = attrs["Item:Length"]?.toLongOrNull() ?: 0L,
                padding = attrs["Item:Padding"]?.toLongOrNull() ?: 0L,
            )
        }.toList()
    }

    private fun extractVideoLengthUs(text: String): Long =
        Regex("""OpCamera:VideoLength="(\d+)"""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun readHeader(file: File): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val len = minOf(HEADER_SCAN.toLong(), file.length()).toInt()
            val buf = ByteArray(len)
            raf.readFully(buf)
            buf
        }
    }.getOrNull()

    private fun findMp4Start(file: File): Long? = runCatching {
        val len = file.length()
        val chunk = 1 shl 20
        val brands = listOf("isom", "mp42", "mp41", "qt  ", "avc1", "iso2", "iso5", "iso6", "M4V ")
        var pos = 0L
        val overlap = 16
        var carry = ByteArray(0)
        while (pos < len) {
            val size = minOf(chunk.toLong(), len - pos).toInt()
            val buf = ByteArray(carry.size + size)
            carry.copyInto(buf)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(pos)
                raf.readFully(buf, carry.size, size)
            }
            var i = 0
            while (i <= buf.size - 12) {
                if (buf[i + 4] == 'f'.code.toByte() && buf[i + 5] == 't'.code.toByte() &&
                    buf[i + 6] == 'y'.code.toByte() && buf[i + 7] == 'p'.code.toByte()
                ) {
                    val boxSize = beInt(buf, i).toLong()
                    val brand = String(buf, i + 8, 4, Charsets.US_ASCII)
                    if (boxSize in 8..(len) && brand in brands) {
                        val abs = pos + (i - carry.size)
                        if (abs > 0) return@runCatching abs
                    }
                }
                i++
            }
            carry = buf.copyOfRange(buf.size - overlap, buf.size)
            pos += size
        }
        null
    }.getOrNull()

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
}
