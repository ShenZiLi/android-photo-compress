package com.photocompress.app.core.png

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.photocompress.app.core.jpeg.JpegCompressor
import com.photocompress.app.core.jpeg.JpegSegments
import com.photocompress.app.core.xmp.PcXmp
import com.photocompress.app.data.media.QualityTier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.math.abs

/**
 * PNG → JPEG：普通照片档位；透明、动画、HDR 或无法保留信息时跳过。
 *
 * 位深只放行 8 位与 16 位。16 位经平台解码后必然降为 8 位每通道，属不可逆精度损失，
 * 已在设置页「压缩PNG」说明中披露；1/2/4 位低色深是位打包存储，本版本不处理。
 */
object PngCompressor {
    const val MAX_FILE_BYTES = 32L * 1024 * 1024
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val archivePrefix = "QingcunPNG\u0000".toByteArray(Charsets.US_ASCII)
    private val critical = setOf("IHDR", "PLTE", "IDAT", "IEND")
    /** 放行的位深；16 位只允许无调色板色彩类型。 */
    private val supportedDepths = setOf(8, 16)
    private val supportedColors = setOf(0, 2, 3, 4, 6)
    private val known = critical + setOf(
        "eXIf", "iTXt", "tEXt", "zTXt", "iCCP", "sRGB", "gAMA", "cHRM", "pHYs", "tIME",
        "bKGD", "tRNS", "sBIT", "hIST", "sPLT", "cICP", "mDCv", "cLLi",
    )
    data class Encoded(val bytes: ByteArray, val quality: Int, val bitDepth: Int)
    data class Probe(val skipReason: String?)
    private data class Chunk(val type: String, val start: Int, val length: Int) {
        val dataStart: Int get() = start + 8
        val end: Int get() = start + length + 12
    }
    private data class Image(
        val bytes: ByteArray, val chunks: List<Chunk>, val width: Int, val height: Int,
        val color: Int, val bitDepth: Int,
    ) {
        val channels: Int get() = when (color) { 0, 3 -> 1; 2 -> 3; 4 -> 2; else -> 4 }
        /** 每样本字节数；16 位为 2。filter 按字节运算，左邻距离必须用它换算。 */
        val sampleBytes: Int get() = bitDepth / 8
        val stride: Int get() = width * channels * sampleBytes
    }
    private fun crcOf(type: String, data: ByteArray): Int = CRC32().apply {
        update(type.toByteArray(Charsets.US_ASCII)); update(data)
    }.value.toInt()

    /** 位深与色彩类型闸门：返回 null 表示放行，否则为跳过原因。 */
    private fun depthReason(bitDepth: Int, color: Int): String? = when {
        bitDepth !in supportedDepths -> "PNG 为 1/2/4 位低色深，本版本不转换，已保留原片"
        color !in supportedColors -> "PNG 色彩类型 $color 未经支持，已保留原片"
        bitDepth == 16 && color == 3 -> "PNG 位深与色彩类型组合非法，已保留原片"
        else -> null
    }
    /** 跳过 IDAT，不把整张 PNG 读入内存。 */
    fun probe(file: File): Probe = runCatching {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() <= MAX_FILE_BYTES) { "PNG 文件过大，已保留原片" }
            val header = ByteArray(8)
            input.readFully(header)
            require(header.contentEquals(signature)) { "PNG 文件无效，已保留原片" }
            var seenHeader = false
            var seenIdat = false
            while (input.filePointer + 12 <= input.length()) {
                val length = input.readInt()
                require(length >= 0 && length.toLong() + 8 <= input.length() - input.filePointer) { "PNG 数据块边界无效" }
                val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                require(type.length == 4 && type.all { it in 'A'..'Z' || it in 'a'..'z' } && type[2] in 'A'..'Z') { "PNG 数据块类型无效" }
                require(type !in setOf("acTL", "fcTL", "fdAT")) { "APNG 动图暂不压缩，已保留原片" }
                require(type in known || (type[0] in 'a'..'z' && type[3] in 'a'..'z')) { "PNG 未知扩展无法安全保留" }
                if (!seenHeader) {
                    require(type == "IHDR" && length == 13) { "PNG 缺少图像头" }
                    val data = ByteArray(13).also(input::readFully)
                    require(crcOf(type, data) == input.readInt()) { "PNG 图像头校验失败" }
                    require(intAt(data, 0) in 1..65536 && intAt(data, 4) > 0 && intAt(data, 0).toLong() * intAt(data, 4) <= 16_000_000) { "PNG 像素过多，已保留原片" }
                    depthReason(data[8].toInt() and 255, data[9].toInt() and 255)?.let { throw IllegalArgumentException(it) }
                    require(data[10].toInt() == 0 && data[11].toInt() == 0 && data[12].toInt() == 0) { "PNG 交错或未知编码暂不处理" }
                    seenHeader = true
                } else {
                    input.seek(input.filePointer + length + 4L)
                }
                if (type == "IDAT") seenIdat = true
                if (type == "IEND") {
                    require(length == 0 && input.filePointer == input.length() && seenIdat) { "PNG 结构不完整或存在未知尾部" }
                    return@use Probe(null)
                }
            }
            throw IllegalArgumentException("PNG 结构不完整，已保留原片")
        }
    }.getOrElse { Probe(it.message ?: "PNG 读取失败，已保留原片") }


    fun compress(bytes: ByteArray, tier: QualityTier, marker: PcXmp.Marker, checkCancelled: () -> Unit): Encoded {
        val image = parse(bytes)
        require(image.chunks.filter { it.type != "IDAT" }.sumOf { it.length.toLong() + 12 } <= 4L * 1024 * 1024) { "PNG 扩展信息过大，已保留原片" }
        require(image.chunks.none { it.type in setOf("cICP", "mDCv", "cLLi") }) { "PNG HDR 信息无法保真转换，已保留原片" }
        validatePixels(image, checkCancelled)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })) { "PNG 解码失败，已保留原片" }
        try {
            require(bitmap.width == image.width && bitmap.height == image.height) { "PNG 解码尺寸变化" }
            val pixels = IntArray(bitmap.width)
            repeat(bitmap.height) { y ->
                checkCancelled()
                bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
                require(pixels.all { (it ushr 24) == 255 }) { "PNG 含透明区域，无法保留透明度，已保留原片" }
            }
            val quality = JpegCompressor.qualityFor(tier)
            val stream = ByteArrayOutputStream()
            require(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) { "PNG 转 JPEG 编码失败" }
            checkCancelled()
            val raw = stream.toByteArray()
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size)) { "输出 JPEG 无法解码" }
            try {
                require(decoded.width == bitmap.width && decoded.height == bitmap.height) {
                    "PNG 解码尺寸变化，已保留原片"
                }
                // 16 位源经 Skia 解出的是扩展 sRGB（scRGB-nl），转 8 位 JPEG 后必然落在标准 sRGB，
                // 这属于已声明的降级路径，不算保真失败。
                require(decoded.colorSpace == bitmap.colorSpace || (image.bitDepth == 16 && decoded.colorSpace?.isSrgb == true)) {
                    "PNG 色彩空间无法完整保留（源 ${bitmap.colorSpace}，输出 ${decoded.colorSpace}），已保留原片"
                }
            } finally { decoded.recycle() }
            val meta = mutableListOf<JpegSegments.Segment>()
            val exif = image.chunks.filter { it.type == "eXIf" }
            require(exif.size <= 1) { "PNG EXIF 结构不明确，已保留原片" }
            exif.singleOrNull()?.let {
                val payload = JpegSegments.EXIF_PREFIX + bytes.copyOfRange(it.dataStart, it.dataStart + it.length)
                require(payload.size <= 65533) { "PNG EXIF 超出 JPEG 容量，已保留原片" }
                meta += JpegSegments.Segment(JpegSegments.MARKER_APP1, payload)
            }
            val xmp = image.chunks.mapNotNull { xmpText(image, it) }
            require(xmp.size <= 1 && (xmp.isEmpty() || xmp.single().contains("<rdf:Description"))) { "PNG XMP 无法完整迁移，已保留原片" }
            val xmpOut = PcXmp.injectAttributes(xmp.singleOrNull(), marker)
            val xmpSegment = JpegSegments.xmpSegment(xmpOut)
            require(xmpSegment.payload.size <= 65533) { "PNG XMP 超出 JPEG 容量，已保留原片" }
            meta += xmpSegment
            // 所有原始 PNG 非像素数据块（含 CRC/顺序）分段归档，不丢弃 JPEG 无对应字段的信息。
            val archive = metadataArchive(image)
            val capacity = 65533 - archivePrefix.size - 8
            val count = (archive.size + capacity - 1) / capacity
            repeat(count) { index ->
                checkCancelled()
                val header = java.nio.ByteBuffer.allocate(8).putInt(index).putInt(count).array()
                meta += JpegSegments.Segment(JpegSegments.MARKER_APP15,
                    archivePrefix + header + archive.copyOfRange(index * capacity, minOf(archive.size, (index + 1) * capacity)))
            }
            val encoded = JpegSegments.split(raw)
            val finalBytes = JpegSegments.assemble(meta + encoded.segments.filter { !JpegSegments.isExif(it) && !JpegSegments.isXmp(it) }, encoded.tail)
            require(JpegCompressor.probeSize(finalBytes) == (image.width to image.height) && PcXmp.read(JpegSegments.xmpTextOf(finalBytes)) == marker) { "JPEG 尺寸或唯一标记未通过" }
            exif.singleOrNull()?.let {
                require(JpegSegments.exifPayload(finalBytes)?.contentEquals(JpegSegments.EXIF_PREFIX + bytes.copyOfRange(it.dataStart, it.dataStart + it.length)) == true) { "PNG EXIF 未完整保留" }
            }
            val archived = ByteArrayOutputStream()
            val parts = JpegSegments.headerSegments(finalBytes).filter { it.marker == JpegSegments.MARKER_APP15 && JpegSegments.startWith(it.payload, archivePrefix) }
            require(parts.size == count) { "PNG 元数据归档数量未通过" }
            parts.forEachIndexed { index, part ->
                val header = java.nio.ByteBuffer.wrap(part.payload, archivePrefix.size, 8)
                require(header.int == index && header.int == count) { "PNG 元数据归档顺序未通过" }
                archived.write(part.payload, archivePrefix.size + 8, part.payload.size - archivePrefix.size - 8)
            }
            require(archived.toByteArray().contentEquals(archive)) { "PNG 元数据未完整保留" }
            checkCancelled()
            return Encoded(finalBytes, quality, image.bitDepth)
        } finally { bitmap.recycle() }
    }

    private fun metadataArchive(image: Image): ByteArray = ByteArrayOutputStream().apply {
        write(signature)
        image.chunks.filter { it.type != "IDAT" }.forEach { write(image.bytes, it.start, it.end - it.start) }
    }.toByteArray()

    private fun xmpText(image: Image, chunk: Chunk): String? {
        if (chunk.type !in setOf("iTXt", "tEXt", "zTXt")) return null
        val data = image.bytes.copyOfRange(chunk.dataStart, chunk.dataStart + chunk.length)
        val zero = data.indexOf(0)
        if (zero < 0 || data.copyOfRange(0, zero).toString(Charsets.ISO_8859_1) != "XML:com.adobe.xmp") return null
        var at = zero + 1
        val compressed: Boolean
        if (chunk.type == "iTXt") {
            require(at + 2 <= data.size && data[at].toInt() in 0..1 && data[at + 1].toInt() == 0) { "PNG XMP 压缩格式无效" }
            compressed = data[at].toInt() == 1
            at += 2
            repeat(2) { while (at < data.size && data[at].toInt() != 0) at++; require(at < data.size) { "PNG XMP 文本边界无效" }; at++ }
        } else if (chunk.type == "zTXt") {
            require(at < data.size && data[at].toInt() == 0) { "PNG XMP 压缩格式无效" }
            at++; compressed = true
        } else compressed = false
        val text = if (compressed) InflaterInputStream(ByteArrayInputStream(data, at, data.size - at)).use {
            val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            var n = it.read(buffer)
            while (n > 0) { require(out.size() + n <= 65533) { "PNG XMP 超出 JPEG 容量" }; out.write(buffer, 0, n); n = it.read(buffer) }
            out.toByteArray()
        } else data.copyOfRange(at, data.size)
        require(text.size <= 65533) { "PNG XMP 超出 JPEG 容量" }
        return text.toString(Charsets.UTF_8).also { require(it.toByteArray(Charsets.UTF_8).contentEquals(text)) { "PNG XMP 编码无效" } }
    }
    private fun parse(bytes: ByteArray): Image {
        require(bytes.size.toLong() <= MAX_FILE_BYTES && bytes.size >= 33 && bytes.copyOfRange(0, 8).contentEquals(signature)) { "PNG 文件无效或过大，已保留原片" }
        val chunks = mutableListOf<Chunk>()
        var at = 8
        var endedIdat = false
        while (at + 12 <= bytes.size) {
            val length = intAt(bytes, at)
            require(length >= 0 && length.toLong() + at + 12 <= bytes.size) { "PNG 数据块边界无效" }
            val type = bytes.copyOfRange(at + 4, at + 8).toString(Charsets.US_ASCII)
            require(type.all { it in 'A'..'Z' || it in 'a'..'z' } && type[2] in 'A'..'Z') { "PNG 数据块类型无效" }
            val crc = CRC32().apply { update(bytes, at + 4, length + 4) }.value.toInt()
            require(crc == intAt(bytes, at + length + 8)) { "PNG 数据块校验失败" }
            require(type !in setOf("acTL", "fcTL", "fdAT")) { "APNG 动图暂不压缩，已保留原片" }
            require(type in known || (type[0] in 'a'..'z' && type[3] in 'a'..'z')) { "PNG 未知扩展无法安全保留" }
            if (type == "IDAT") require(!endedIdat) { "PNG 图像数据顺序无效" }
            else if (chunks.any { it.type == "IDAT" }) endedIdat = true
            chunks += Chunk(type, at, length)
            at += length + 12
            if (type == "IEND") break
        }
        require(at == bytes.size && chunks.firstOrNull()?.let { it.type == "IHDR" && it.length == 13 } == true &&
            chunks.count { it.type == "IHDR" } == 1 && chunks.last().let { it.type == "IEND" && it.length == 0 } &&
            chunks.any { it.type == "IDAT" }) { "PNG 结构不完整或存在未知尾部" }
        val width = intAt(bytes, 16)
        val height = intAt(bytes, 20)
        val color = bytes[25].toInt() and 255
        require(width in 1..65536 && height > 0 && width.toLong() * height <= 16_000_000) { "PNG 像素过多，已保留原片" }
        depthReason(bytes[24].toInt() and 255, color)?.let { throw IllegalArgumentException(it) }
        require(bytes[26].toInt() == 0 && bytes[27].toInt() == 0 && bytes[28].toInt() == 0) { "PNG 交错或未知编码暂不处理" }
        val palette = chunks.filter { it.type == "PLTE" }
        require(palette.size <= 1 && palette.all { it.length in 3..768 && it.length % 3 == 0 && it.start < chunks.first { c -> c.type == "IDAT" }.start }) { "PNG 调色板无效" }
        require(color != 3 || palette.size == 1) { "PNG 缺少调色板" }
        require(color !in setOf(0, 4) || palette.isEmpty()) { "PNG 灰度图不允许调色板" }
        val transparency = chunks.filter { it.type == "tRNS" }
        require(transparency.size <= 1 && transparency.all {
            it.start < chunks.first { c -> c.type == "IDAT" }.start && when (color) {
                0 -> it.length == 2
                2 -> it.length == 6
                3 -> it.length in 1..(palette.single().length / 3)
                else -> false
            }
        }) { "PNG 透明度结构无效" }
        return Image(bytes, chunks, width, height, color, bytes[24].toInt() and 255)
    }

    private class Pixels(val image: Image, val stream: InflaterInputStream, val inflater: Inflater, val input: SequenceInputStream) {
        var previous = ByteArray(image.stride)
        var filter = 0
        fun next(): ByteArray {
            filter = stream.read()
            require(filter in 0..4) { "PNG 行过滤无效" }
            val row = ByteArray(image.stride)
            var read = 0
            while (read < row.size) {
                val n = stream.read(row, read, row.size - read)
                require(n > 0) { "PNG 像素数据不完整" }
                read += n
            }
            val bpp = image.channels * image.sampleBytes
            for (x in row.indices) row[x] = ((row[x].toInt() and 255) + predictor(row, previous, x, bpp, filter)).toByte()
            if (image.color == 3) {
                val colors = image.chunks.single { it.type == "PLTE" }.length / 3
                require(row.all { (it.toInt() and 255) < colors }) { "PNG 调色板索引无效" }
            }
            return row
        }
        fun finish() {
            require(stream.read() == -1 && inflater.finished() && inflater.remaining == 0 && input.read() == -1) { "PNG 像素数据长度无效" }
        }
    }

    private fun <T> withPixels(image: Image, block: (Pixels) -> T): T {
        val parts = image.chunks.filter { it.type == "IDAT" }.map { ByteArrayInputStream(image.bytes, it.dataStart, it.length) }
        val input = SequenceInputStream(Collections.enumeration(parts))
        val inflater = Inflater()
        try {
            InflaterInputStream(input, inflater).use { stream -> return block(Pixels(image, stream, inflater, input)) }
        } finally {
            inflater.end()
        }
    }

    private fun validatePixels(image: Image, checkCancelled: () -> Unit) = withPixels(image) { pixels ->
        repeat(image.height) {
            checkCancelled()
            val row = pixels.next()
            pixels.previous = row
        }
        pixels.finish()
    }

    /** bpp 为每像素字节数；滤波在字节层运算，16 位只需把左邻距离放大到样本字节数。 */
    private fun predictor(row: ByteArray, previous: ByteArray, x: Int, bpp: Int, filter: Int): Int {
        val a = if (x >= bpp) row[x - bpp].toInt() and 255 else 0
        val b = previous[x].toInt() and 255
        val c = if (x >= bpp) previous[x - bpp].toInt() and 255 else 0
        return when (filter) {
            0 -> 0; 1 -> a; 2 -> b; 3 -> (a + b) / 2
            else -> { val p = a + b - c; val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
                if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c }
        }
    }

    private fun intAt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 255) shl 24) or ((bytes[at + 1].toInt() and 255) shl 16) or
            ((bytes[at + 2].toInt() and 255) shl 8) or (bytes[at + 3].toInt() and 255)

}
