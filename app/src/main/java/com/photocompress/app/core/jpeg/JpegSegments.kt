package com.photocompress.app.core.jpeg

import java.io.ByteArrayOutputStream

/**
 * JPEG 段级解析与重组。
 *
 * 压缩后的主图由平台编码器生成，只带最小 APP0；这里把原文件的 APPn 段
 * （EXIF / XMP / MPF）原样搬到新图上，保证元信息逐字节一致（F5 / AC3）。
 */
object JpegSegments {

    const val MARKER_SOI = 0xD8
    const val MARKER_EOI = 0xD9
    const val MARKER_SOS = 0xDA
    const val MARKER_APP0 = 0xE0
    const val MARKER_APP1 = 0xE1
    const val MARKER_APP2 = 0xE2

    val EXIF_PREFIX = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    val XMP_PREFIX = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    val MPF_PREFIX = "MPF\u0000".toByteArray(Charsets.ISO_8859_1)

    data class Segment(val marker: Int, val payload: ByteArray)

    data class Split(val segments: List<Segment>, val tail: ByteArray)

    /** 校验是否为 JPEG（SOI 开头）。 */
    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size > 4 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == MARKER_SOI

    /**
     * 拆分为 [SOI 后的各段] 与 [从 SOS 段开始的剩余字节]。
     * 遇到 SOS 即停止扫描（其后为熵编码数据，按字节透传）。
     */
    fun split(bytes: ByteArray): Split {
        val segments = ArrayList<Segment>()
        var i = 2 // 跳过 SOI
        while (i + 3 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) break
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == MARKER_SOS || marker == MARKER_EOI) break
            // 允许 0xFF 填充
            if (marker == 0xFF) { i++; continue }
            val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > bytes.size) break
            val payload = bytes.copyOfRange(i + 4, i + 2 + len)
            segments += Segment(marker, payload)
            i += 2 + len
        }
        val tail = if (i < bytes.size) bytes.copyOfRange(i, bytes.size) else ByteArray(0)
        return Split(segments, tail)
    }

    /** 组装：SOI + 各段 + tail。 */
    fun assemble(segments: List<Segment>, tail: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(MARKER_SOI)
        for (s in segments) {
            out.write(0xFF); out.write(s.marker)
            val len = s.payload.size + 2
            out.write((len ushr 8) and 0xFF); out.write(len and 0xFF)
            out.write(s.payload)
        }
        out.write(tail)
        return out.toByteArray()
    }

    fun startWith(payload: ByteArray, prefix: ByteArray): Boolean {
        if (payload.size < prefix.size) return false
        for (i in prefix.indices) if (payload[i] != prefix[i]) return false
        return true
    }

    fun isExif(s: Segment) = s.marker == MARKER_APP1 && startWith(s.payload, EXIF_PREFIX)
    fun isXmp(s: Segment) = s.marker == MARKER_APP1 && startWith(s.payload, XMP_PREFIX)
    fun isMpf(s: Segment) = s.marker == MARKER_APP2 && startWith(s.payload, MPF_PREFIX)
    fun isJfif(s: Segment) = s.marker == MARKER_APP0 && startWith(s.payload, "JFIF\u0000".toByteArray(Charsets.ISO_8859_1))

    fun xmpText(s: Segment): String? =
        if (isXmp(s)) String(s.payload, XMP_PREFIX.size, s.payload.size - XMP_PREFIX.size, Charsets.UTF_8) else null

    fun xmpSegment(xmp: String): Segment =
        Segment(MARKER_APP1, XMP_PREFIX + xmp.toByteArray(Charsets.UTF_8))

    /** 取原图第一个 EXIF 段的完整字节（含 "Exif\0\0" 前缀）。 */
    fun exifPayload(bytes: ByteArray): ByteArray? =
        split(bytes).segments.firstOrNull { isExif(it) }?.payload

    /** 取原图 XMP 文本。 */
    fun xmpTextOf(bytes: ByteArray): String? =
        split(bytes).segments.firstOrNull { isXmp(it) }?.let { xmpText(it) }

    /**
     * 用原图的元信息段（APP0/APP1/APP2 中的 JFIF / EXIF / XMP / MPF）
     * 替换编码结果的对应段，其余保留编码结果的。
     * [mpfOverride] 非空时用其替换原 MPF（用于 MPEntry 偏移重算）。
     */
    fun transplantMetadata(
        encoded: ByteArray,
        original: ByteArray,
        xmpOverride: String?,
        mpfOverride: ByteArray? = null,
    ): ByteArray {
        val enc = split(encoded)
        val orig = split(original)

        val keep = ArrayList<Segment>()
        // 编码器默认的 JFIF 段：若原图有则用原图的
        val origJfif = orig.segments.firstOrNull { isJfif(it) }
        if (origJfif != null) keep += origJfif

        val origExif = orig.segments.firstOrNull { isExif(it) }
        if (origExif != null) keep += origExif

        val mpf = mpfOverride ?: orig.segments.firstOrNull { isMpf(it) }?.payload
        if (mpf != null) keep += Segment(MARKER_APP2, mpf)

        val xmp = xmpOverride ?: orig.segments.firstOrNull { isXmp(it) }?.let { xmpText(it) }
        if (xmp != null) keep += xmpSegment(xmp)

        // 其余非元信息段（DQT/DHT/SOF 等）来自编码结果，去掉它自带的 APP0/APP1
        val encRest = enc.segments.filter {
            it.marker != MARKER_APP0 && it.marker != MARKER_APP1 && it.marker != MARKER_APP2
        }
        return assemble(keep + encRest, enc.tail)
    }

    /** 取原图 MPF 段 payload（含 "MPF\0" 前缀）。 */
    fun mpfPayloadOf(bytes: ByteArray): ByteArray? =
        split(bytes).segments.firstOrNull { isMpf(it) }?.payload
}
