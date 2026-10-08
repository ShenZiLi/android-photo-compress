package com.photocompress.app.core.heif

import com.photocompress.app.core.xmp.PcXmp
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** 只替换主图 HEVC extents；原 item ID、引用、EXIF、ICC、辅助项及厂商尾部原样保留。 */
internal class HeicContainer(private val bytes: ByteArray) {
    data class Box(val type: String, val start: Int, val size: Int, val header: Int = 8) {
        val data get() = start + header
        val end get() = start + size
    }
    data class Extent(val offset: Int, val length: Int)
    data class Location(val id: Int, val method: Int, val dataReferenceIndex: Int, val extents: List<Extent>)
    data class Association(val index: Int, val essential: Boolean)
    private val top = boxes(bytes, 0, bytes.size, allowTail = true)
    private val tail = bytes.copyOfRange(top.last().end, bytes.size)
    private val meta = top.single { it.type == "meta" }
    private val children = boxes(bytes, meta.data + 4, meta.end)
    private val mdat = top.single { it.type == "mdat" }
    private val idat = children.singleOrNull { it.type == "idat" }
    private val iloc = children.single { it.type == "iloc" }
    private val iprp = children.single { it.type == "iprp" }
    private val propertyBoxes = boxes(bytes, iprp.data, iprp.end)
    private val ipco = propertyBoxes.single { it.type == "ipco" }
    private val properties = boxes(bytes, ipco.data, ipco.end)
    private val ipma = propertyBoxes.single { it.type == "ipma" }
    private val dataReferenceCount = readDataReferenceCount()
    private val locations = readLocations()
    private val types = readTypes()
    private val associations = readAssociations()
    private val references = readReferences()
    val primaryId: Int = children.single { it.type == "pitm" }.let {
        val c = Cursor(bytes, it.data, it.end)
        val version = c.u8(); c.skip(3)
        require(version in 0..1)
        val id = if (version == 0) c.u16() else c.u32(); c.finish(); id
    }
    private val imageSize = property(primaryId, "ispe").let {
        val c = Cursor(bytes, it.data, it.end); require(c.u32() == 0)
        val size = c.u32() to c.u32(); c.finish(); size
    }
    val width get() = imageSize.first
    val height get() = imageSize.second
    val grid: Boolean get() = types[primaryId] == "grid"
    private val tiles: List<Int> = if (grid) references.filter { it.first == "dimg" && it.second == primaryId }
        .single().third else listOf(primaryId)

    init {
        require(bytes.size <= MAX_FILE_BYTES) { "HEIC 文件超过 64 MiB，已保留原片" }
        require(width > 0 && height > 0) { "HEIC 图像尺寸无效，已保留原片" }
        require(width.toLong() * height <= MAX_PIXELS) { "HEIC 像素超过 6400 万，已保留原片" }
        require(top.first().type == "ftyp" && top.first().header == 8 && raw(top.first()).let {
            it.size >= 20 && String(it, 8, 4, Charsets.US_ASCII) in setOf("heic", "heix", "mif1")
        }) { "非静态 HEIC 容器" }
        require(top.none { it.type in setOf("moov", "moof", "sidx") } && bytes[meta.data].toInt() == 0) { "HEIC 序列或未知容器暂不处理" }
        require(top.all { it.type in setOf("ftyp", "meta", "mdat", "free", "skip", "uuid", "QTI ") }) { "HEIC 未知顶层结构，已保留原片" }
        val unknownChildren = children.filter { it.type !in setOf("hdlr", "pitm", "iloc", "iinf", "iprp", "iref", "idat", "dinf", "free", "skip") }
        require(unknownChildren.isEmpty()) { "HEIC 扩展结构暂不处理：${unknownChildren.map { it.type }.distinct().joinToString(", ")}" }
        require(propertyBoxes.size == 2 && properties.size <= 4096 && locations.keys == types.keys) { "HEIC 项目索引不完整" }
        require(types[primaryId] in setOf("grid", "hvc1") && tiles.size in 1..256 && tiles.distinct().size == tiles.size) { "HEIC 主图结构暂不处理" }
        require(references.none { it.first == "auxl" } && references.all { it.first in setOf("dimg", "thmb", "cdsc") }) { "HEIC 深度、透明或未知辅助图暂不处理" }
        require(references.all { it.second in types && it.third.all(types::containsKey) }) { "HEIC 引用越界" }
        require(references.none { it.first == "dimg" && it.second != primaryId && it.third.any(tiles::contains) }) { "HEIC 主图数据被其他图片共用" }
        if (grid) {
            val data = payload(primaryId)
            require(data.size in setOf(8, 12) && data[0].toInt() == 0 && (data[1].toInt() and 254) == 0) { "HEIC 网格结构无效" }
            require(((data[2].toInt() and 255) + 1) * ((data[3].toInt() and 255) + 1) == tiles.size) { "HEIC 网格数量不符" }
            val c = Cursor(data, 4, data.size)
            val size = if (data[1].toInt() == 0) c.u16() to c.u16() else c.u32() to c.u32()
            c.finish(); require(size == imageSize) { "HEIC 网格尺寸不符" }
        }
        for (id in (tiles + primaryId).distinct()) {
            require(associations[id].orEmpty().all { properties[it.index - 1].type in setOf("hvcC", "ispe", "colr", "pixi") }) { "HEIC 旋转、裁切或扩展属性暂不处理" }
            for (p in associated(id).filter { it.type == "colr" }) {
                val tag = String(bytes, p.data, 4, Charsets.US_ASCII)
                require(tag in setOf("prof", "rICC", "nclx")) { "HEIC 色彩信息未知" }
                if (tag == "nclx") {
                    val c = Cursor(bytes, p.data + 4, p.end)
                    c.u16(); val transfer = c.u16()
                    require(transfer !in setOf(16, 18)) { "HEIC HDR 不能降级，已保留原片" }
                }
            }
            for (p in associated(id).filter { it.type == "pixi" }) {
                val c = Cursor(bytes, p.data, p.end); require(c.u32() == 0)
                val count = c.u8(); require(count == 3 && List(count) { c.u8() }.all { it == 8 }) { "HEIC 高位深或透明图暂不处理" }; c.finish()
            }
        }
        for (id in tiles) {
            require(types[id] == "hvc1") { "HEIC 非 HEVC 主图暂不处理" }
            val config = property(id, "hvcC")
            require(config.end - config.data >= 23 && (bytes[config.data + 1].toInt() and 31) in setOf(1, 3) &&
                (bytes[config.data + 17].toInt() and 7) == 0 && (bytes[config.data + 18].toInt() and 7) == 0) { "HEIC 仅处理 8 位 SDR，已保留原片" }
        }
        // 每个外部 extent 必须落在唯一 mdat 内；重叠意味着无法独立替换主图。
        val ranges = locations.values.filter { it.method == 0 }.flatMap { it.extents }.sortedBy { it.offset }
        require(ranges.all { it.offset >= mdat.data && it.length > 0 && it.offset.toLong() + it.length <= mdat.end }) { "HEIC 图像偏移无效" }
        require(ranges.zipWithNext().all { (a, b) -> a.offset.toLong() + a.length <= b.offset }) { "HEIC 图像区间重叠" }
    }

    private fun raw(box: Box) = bytes.copyOfRange(box.start, box.end)
    private fun associated(id: Int) = associations[id].orEmpty().map { properties[it.index - 1] }
    private fun property(id: Int, type: String) = associated(id).single { it.type == type }
    private fun payload(id: Int): ByteArray = ByteArrayOutputStream().apply {
        val location = locations.getValue(id)
        val base = if (location.method == 1) requireNotNull(idat).data else 0
        for (e in location.extents) {
            require(e.offset >= 0 && e.length > 0 && base.toLong() + e.offset + e.length <= (if (location.method == 1) requireNotNull(idat).end else bytes.size))
            write(bytes, base + e.offset, e.length)
        }
    }.toByteArray()

    private fun readTypes(): Map<Int, String> {
        val iinf = children.single { it.type == "iinf" }
        val c = Cursor(bytes, iinf.data, iinf.end); val version = c.u8(); c.skip(3)
        require(version in 0..1); val count = if (version == 0) c.u16() else c.u32()
        val entries = boxes(bytes, c.position, iinf.end)
        require(entries.size == count && count <= 4096)
        return entries.associate {
            require(it.type == "infe"); val r = Cursor(bytes, it.data, it.end)
            val v = r.u8(); r.skip(3); require(v in 2..3)
            val id = if (v == 2) r.u16() else r.u32()
            require(r.u16() == 0) { "HEIC 加密项目暂不处理" }
            id to r.text(4)
        }.also { require(it.size == count) }
    }
    private fun readDataReferenceCount(): Int {
        val containers = children.filter { it.type == "dinf" }
        require(containers.size <= 1) { "HEIC 数据引用表重复" }
        val dinf = containers.singleOrNull() ?: return 0
        val entries = boxes(bytes, dinf.data, dinf.end)
        require(entries.size == 1 && entries.single().type == "dref") { "HEIC 数据引用表结构未知" }
        val dref = entries.single()
        val c = Cursor(bytes, dref.data, dref.end)
        require(c.u32() == 0) { "HEIC 数据引用表版本或标志未知" }
        val count = c.u32(); require(count <= 4096) { "HEIC 数据引用表过大" }
        val references = boxes(bytes, c.position, dref.end)
        require(references.size == count) { "HEIC 数据引用表数量不符" }
        for (entry in references) {
            require(entry.type == "url ") { "HEIC 数据引用类型暂不处理：${entry.type}" }
            val r = Cursor(bytes, entry.data, entry.end)
            require(r.u32() == 1) { "HEIC 外部数据引用或未知引用标志暂不处理" }
            r.finish()
        }
        return count
    }
    private fun readLocations(): Map<Int, Location> {
        val c = Cursor(bytes, iloc.data, iloc.end); val version = c.u8(); c.skip(3); require(version in 0..2)
        val a = c.u8(); val b = c.u8(); val offsetSize = a ushr 4; val lengthSize = a and 15
        val baseSize = b ushr 4; val indexSize = if (version == 0) 0 else b and 15
        require(listOf(offsetSize, lengthSize, baseSize, indexSize).all { it in setOf(0, 4, 8) }) { "HEIC 偏移宽度未知" }
        val count = if (version < 2) c.u16() else c.u32(); require(count in 1..4096)
        val result = LinkedHashMap<Int, Location>()
        repeat(count) {
            val id = if (version < 2) c.u16() else c.u32()
            val method = if (version > 0) c.u16() else 0
            val dataReferenceIndex = c.u16()
            require(method in 0..1 && (dataReferenceIndex == 0 || method == 0 && dataReferenceIndex in 1..dataReferenceCount)) { "HEIC 外部数据引用或引用索引无效" }
            val base = c.number(baseSize); val n = c.u16(); require(n in 1..4096)
            val extents = List(n) {
                require(c.number(indexSize) == 0) { "HEIC extent 索引未知" }
                val offset = base.toLong() + c.number(offsetSize); require(offset <= Int.MAX_VALUE)
                Extent(offset.toInt(), c.number(lengthSize))
            }
            require(result.put(id, Location(id, method, dataReferenceIndex, extents)) == null)
        }
        c.finish(); return result
    }
    private fun readAssociations(): Map<Int, List<Association>> {
        val c = Cursor(bytes, ipma.data, ipma.end); val v = c.u8(); val flags = c.number(3)
        require(v in 0..1 && flags in 0..1)
        val count = c.u32(); require(count <= 4096)
        val result = LinkedHashMap<Int, List<Association>>()
        repeat(count) {
            val id = if (v == 0) c.u16() else c.u32()
            val list = List(c.u8()) {
                val value = if (flags == 1) c.u16() else c.u8(); val mask = if (flags == 1) 32768 else 128
                Association(value and (mask - 1), value and mask != 0)
            }
            require(list.all { it.index in 1..properties.size } && result.put(id, list) == null)
        }
        c.finish(); return result
    }
    private fun readReferences(): List<Triple<String, Int, List<Int>>> {
        val ref = children.singleOrNull { it.type == "iref" } ?: return emptyList()
        val c = Cursor(bytes, ref.data, ref.end); val v = c.u8(); c.skip(3); require(v in 0..1)
        return boxes(bytes, c.position, ref.end).map {
            val r = Cursor(bytes, it.data, it.end)
            val from = if (v == 0) r.u16() else r.u32(); val count = r.u16(); require(count <= 4096)
            val to = List(count) { if (v == 0) r.u16() else r.u32() }; r.finish()
            Triple(it.type, from, to)
        }
    }

    fun rebuild(encoded: HeicContainer, marker: PcXmp.Marker): ByteArray {
        require(encoded.imageSize == imageSize && encoded.grid == grid && encoded.tiles.size == tiles.size) { "HEIC 编码网格与原图不一致" }
        if (grid) require(encoded.payload(encoded.primaryId).contentEquals(payload(primaryId))) { "HEIC 网格布局变化" }
        val replacements = LinkedHashMap<Int, ByteArray>()
        val newProperties = properties.map(::raw).toMutableList()
        val swaps = mutableMapOf<Int, Int>()
        tiles.zip(encoded.tiles).forEach { (old, new) ->
            require(locations.getValue(old).method == 0 && locations.getValue(old).extents.size == 1) { "HEIC 主图分段结构暂不处理" }
            require(raw(property(old, "ispe")).contentEquals(encoded.raw(encoded.property(new, "ispe")))) { "HEIC 图像块尺寸变化" }
            replacements[old] = encoded.payload(new)
            val config = encoded.raw(encoded.property(new, "hvcC"))
            var index = newProperties.indexOfFirst { it.contentEquals(config) }
            if (index < 0) { newProperties += config; index = newProperties.lastIndex }
            swaps[old] = index + 1
        }
        require(newProperties.size < 32768)
        val newIpma = box("ipma", output {
            writeInt(0x01000001); writeInt(associations.size)
            for ((id, list) in associations) {
                writeInt(id); writeByte(list.size)
                for (a in list) {
                    val index = if (id in swaps && properties[a.index - 1].type == "hvcC") swaps.getValue(id) else a.index
                    writeShort(index or if (a.essential) 32768 else 0)
                }
            }
        })
        val newIprp = box("iprp", box("ipco", join(newProperties)) + newIpma)
        val offsets = LinkedHashMap<Pair<Int, Int>, Extent>()
        val all = locations.values.filter { it.method == 0 }.flatMap { l -> l.extents.mapIndexed { index, e -> Triple(l.id, index, e) } }.sortedBy { it.third.offset }
        val data = ByteArrayOutputStream(); var at = mdat.data
        for ((id, index, e) in all) {
            data.write(bytes, at, e.offset - at)
            val value = replacements[id] ?: bytes.copyOfRange(e.offset, e.offset + e.length)
            offsets[id to index] = Extent(data.size(), value.size)
            data.write(value); at = e.offset + e.length
        }
        data.write(bytes, at, mdat.end - at)
        fun newMeta(mdatStart: Int): ByteArray {
            val newIloc = box("iloc", output {
                writeInt(0x02000000); writeByte(0x44); writeByte(0); writeInt(locations.size)
                for ((id, l) in locations) {
                    writeInt(id); writeShort(l.method); writeShort(l.dataReferenceIndex); writeShort(l.extents.size)
                    l.extents.forEachIndexed { index, e ->
                        val new = if (l.method == 0) offsets.getValue(id to index) else e
                        writeInt(new.offset + if (l.method == 0) mdatStart else 0); writeInt(new.length)
                    }
                }
            })
            return box("meta", bytes.copyOfRange(meta.data, meta.data + 4) + join(children.map {
                when (it.type) { "iloc" -> newIloc; "iprp" -> newIprp; else -> raw(it) }
            }))
        }
        val placeholder = newMeta(0)
        val mdatStart = top.takeWhile { it != mdat }.sumOf { if (it == meta) placeholder.size else it.size } + 8
        val result = join(top.map { when (it) { meta -> newMeta(mdatStart); mdat -> box("mdat", data.toByteArray()); else -> raw(it) } }) +
            box("uuid", markerUuid + PcXmp.injectAttributes(null, marker).toByteArray(Charsets.UTF_8)) + tail
        val check = HeicContainer(result)
        require(check.primaryId == primaryId && check.types == types && check.references == references && check.imageSize == imageSize)
        require(check.locations.mapValues { it.value.dataReferenceIndex } == locations.mapValues { it.value.dataReferenceIndex }) { "HEIC 数据引用索引未完整保留" }
        val originalChildren = children.filter { it.type !in setOf("iloc", "iprp") }.map(::raw)
        val retainedChildren = check.children.filter { it.type !in setOf("iloc", "iprp") }.map(check::raw)
        require(originalChildren.size == retainedChildren.size && originalChildren.zip(retainedChildren).all { (a, b) -> a.contentEquals(b) }) { "HEIC 元数据框未完整保留" }
        require(check.tail.contentEquals(tail) && readMarker(result) == marker) { "HEIC 标记或厂商尾部校验失败" }
        for (id in types.keys - tiles.toSet()) require(check.payload(id).contentEquals(payload(id))) { "HEIC 非主图数据未完整保留" }
        for ((id, list) in associations) {
            val before = list.filter { id !in tiles || properties[it.index - 1].type != "hvcC" }.map { raw(properties[it.index - 1]) }
            val after = check.associations.getValue(id).filter { id !in tiles || check.properties[it.index - 1].type != "hvcC" }.map { check.raw(check.properties[it.index - 1]) }
            require(before.size == after.size && before.zip(after).all { (a, b) -> a.contentEquals(b) }) { "HEIC 属性未完整保留" }
        }
        return result
    }

    companion object {
        const val MAX_FILE_BYTES = 64 * 1024 * 1024
        const val MAX_PIXELS = 64_000_000L
        private val markerUuid = UUID.fromString("45609067-7c74-4c16-b9da-99c5dd997ea3").let {
            java.nio.ByteBuffer.allocate(16).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array()
        }
        private fun output(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply {
            DataOutputStream(this).use(block)
        }.toByteArray()
        private fun join(parts: List<ByteArray>) = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        private fun box(type: String, data: ByteArray) = output { writeInt(data.size + 8); writeBytes(type); write(data) }
        private fun boxes(bytes: ByteArray, start: Int, end: Int, allowTail: Boolean = false): List<Box> {
            val result = mutableListOf<Box>(); var at = start
            while (at.toLong() + 8 <= end) {
                val c = Cursor(bytes, at, end); val n = c.u32(); val type = c.text(4)
                if (allowTail && type.any { it !in ' '..'~' }) break
                val header = if (n == 1) 16 else 8; val size = if (n == 1) c.number(8) else if (n == 0) end - at else n
                require(size >= header && at.toLong() + size <= end && result.size < 8192) { "HEIC 数据框边界无效" }
                result += Box(type, at, size, header); at += size
            }
            require(allowTail || at == end) { "HEIC 数据框不完整" }
            return result
        }
        private fun readMarker(bytes: ByteArray): PcXmp.Marker? = boxes(bytes, 0, bytes.size, true).mapNotNull {
            if (it.type == "uuid" && it.size >= it.header + 16 && bytes.copyOfRange(it.data, it.data + 16).contentEquals(markerUuid)) {
                PcXmp.read(String(bytes, it.data + 16, it.end - it.data - 16, Charsets.UTF_8))
            } else null
        }.singleOrNull()
        /** 只读取顶层头部及自有 XMP，不为媒体扫描加载 HEVC 像素数据。 */
        fun markerOf(file: File): PcXmp.Marker? = runCatching {
            RandomAccessFile(file, "r").use { input ->
                var count = 0
                while (input.filePointer + 8 <= input.length() && count++ < 8192) {
                    val at = input.filePointer; var size = input.readInt().toLong() and 0xffffffffL
                    val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                    if (type.any { it !in ' '..'~' }) break
                    val header = if (size == 1L) 16 else 8
                    if (size == 1L) size = input.readLong()
                    if (size == 0L) size = input.length() - at
                    require(size >= header && size <= input.length() - at)
                    if (type == "uuid" && size in (header + 16L)..(header + 65536L)) {
                        val uuid = ByteArray(16).also(input::readFully)
                        if (uuid.contentEquals(markerUuid)) return@use PcXmp.read(String(ByteArray((size - header - 16).toInt()).also(input::readFully), Charsets.UTF_8))
                    }
                    input.seek(at + size)
                }
                null
            }
        }.getOrNull()
    }
    private class Cursor(private val bytes: ByteArray, var position: Int, private val end: Int) {
        fun u8(): Int { require(position < end); return bytes[position++].toInt() and 255 }
        fun u16() = number(2)
        fun u32() = number(4)
        fun number(n: Int): Int {
            require(n in 0..8 && position.toLong() + n <= end)
            var value = 0L
            repeat(n) { require(value <= Int.MAX_VALUE.toLong() ushr 8); value = (value shl 8) or u8().toLong() }
            require(value <= Int.MAX_VALUE); return value.toInt()
        }
        fun text(n: Int): String { require(position + n <= end); return String(bytes, position, n, Charsets.US_ASCII).also { position += n } }
        fun skip(n: Int) { require(position + n <= end); position += n }
        fun finish() { require(position == end) { "HEIC 结构尾部未知" } }
    }
}
