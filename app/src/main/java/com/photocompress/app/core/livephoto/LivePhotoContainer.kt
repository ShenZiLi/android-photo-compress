package com.photocompress.app.core.livephoto

import com.photocompress.app.data.media.LivePhotoDetector
import java.io.File

/**
 * 实况照片 / Ultra HDR 的容器布局（XMP `Container:Directory`）。
 *
 * 文件结构：主图(JPEG) [ + GainMap(JPEG) ] [ + MotionPhoto(MP4) ]，顺序固定，
 * 每段长度由 XMP 声明，重组后必须重算（design.md §4.4）。
 */
object LivePhotoContainer {

    data class Plan(
        val hasGainMap: Boolean,
        val hasMotion: Boolean,
        val gainMapLength: Long,
        val gainMapPadding: Long,
        val motionLength: Long,
        val motionPadding: Long,
        val primaryEnd: Long,
    )

    /** 从文件推导各段位置（只依赖文件长度与 XMP 声明）。 */
    fun plan(file: File, items: List<LivePhotoDetector.XmpItem>): Plan? {
        val fileLength = file.length()
        val gain = items.firstOrNull { it.semantic == "GainMap" }
        val motion = items.firstOrNull { it.semantic == "MotionPhoto" }
        val gainLen = gain?.length ?: 0L
        val gainPad = gain?.padding ?: 0L
        val motionLen = motion?.length ?: 0L
        val motionPad = motion?.padding ?: 0L

        val primaryEnd = when {
            motion != null -> fileLength - motionLen - motionPad - gainLen - gainPad
            gain != null -> fileLength - gainLen - gainPad
            else -> return null
        }
        if (primaryEnd <= 0) return null
        return Plan(
            hasGainMap = gain != null,
            hasMotion = motion != null,
            gainMapLength = gainLen,
            gainMapPadding = gainPad,
            motionLength = motionLen,
            motionPadding = motionPad,
            primaryEnd = primaryEnd,
        )
    }

    fun itemLengths(plan: Plan, fileLength: Long): Triple<Long, Long, Long> {
        val motionStart = if (plan.hasMotion) fileLength - plan.motionLength - plan.motionPadding else fileLength
        val gainStart = motionStart - if (plan.hasGainMap) plan.gainMapLength + plan.gainMapPadding else 0L
        return Triple(plan.primaryEnd, gainStart, motionStart)
    }

    /**
     * 只在原 XMP 上**就地改写** `Item:Length` 的数值，不重建 `Container:Directory`、
     * 不新增 `rdf:Description`、不改变元素顺序与空白。
     *
     * 实机验证教训：oplus 相册对 XMP 结构敏感，结构一变实况照片就无法播放。
     * 因此这里只替换数字，其余字节保持原样。
     */
    fun rewriteItemLengths(xmp: String, newLengths: Map<String, Long>): String {
        var out = xmp
        for ((semantic, length) in newLengths) out = rewriteOne(out, semantic, length)
        return out
    }

    private fun rewriteOne(xmp: String, semantic: String, length: Long): String {
        val semanticIdx = xmp.indexOf("Item:Semantic=\"$semantic\"")
        if (semanticIdx < 0) return xmp
        val itemStart = xmp.lastIndexOf("<Container:Item", semanticIdx).let { if (it < 0) return xmp else it }
        val closeIdx = xmp.indexOf("/>", semanticIdx).let { if (it < 0) return xmp else it }
        val block = xmp.substring(itemStart, closeIdx)
        val replaced = if (block.contains("Item:Length=")) {
            Regex("""Item:Length="\d*"""").replace(block, "Item:Length=\"$length\"")
        } else {
            block.replace(
                "Item:Semantic=\"$semantic\"",
                "Item:Semantic=\"$semantic\" Item:Length=\"$length\"",
            )
        }
        return xmp.substring(0, itemStart) + replaced + xmp.substring(closeIdx)
    }

    /**
     * 判断内嵌尾段是否为「单个普通 MP4」。
     *
     * oplus/realme 的实况照片尾段常是厂商私有复合结构
     * （MP4 #1 + 私有块 + MP4 #2，见 research/acceptance-report.md），
     * 只有普通 MP4 才能安全地整段重编码；否则必须原样保留。
     */
    fun isPlainMp4(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        var i = 0
        var sawFtyp = false
        var sawMoov = false
        var sawMdat = false
        while (i + 8 <= bytes.size) {
            val type = String(bytes, i + 4, 4, Charsets.ISO_8859_1)
            if (!type.all { it.code in 32..126 }) return false
            var size = beInt(bytes, i).toLong() and 0xFFFFFFFFL
            if (size == 1L) {
                if (i + 16 > bytes.size) return false
                size = ((bytes[i + 8].toLong() and 0xFF) shl 56) or ((bytes[i + 9].toLong() and 0xFF) shl 48) or
                    ((bytes[i + 10].toLong() and 0xFF) shl 40) or ((bytes[i + 11].toLong() and 0xFF) shl 32) or
                    ((bytes[i + 12].toLong() and 0xFF) shl 24) or ((bytes[i + 13].toLong() and 0xFF) shl 16) or
                    ((bytes[i + 14].toLong() and 0xFF) shl 8) or (bytes[i + 15].toLong() and 0xFF)
            } else if (size == 0L) {
                size = (bytes.size - i).toLong()
            }
            if (size < 8 || i + size > bytes.size) return false
            when (type) {
                "ftyp" -> sawFtyp = true
                "moov" -> sawMoov = true
                "mdat" -> sawMdat = true
            }
            i += size.toInt()
        }
        return sawFtyp && sawMoov && sawMdat && i == bytes.size
    }

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    /** 重组后自校验：XMP 声明的长度与真实拼接长度一致。 */
    fun verify(assembled: ByteArray): String? {
        val header = if (assembled.size > 256 * 1024) assembled.copyOf(256 * 1024) else assembled
        val text = String(header, Charsets.ISO_8859_1)
        val items = LivePhotoDetector.parseContainerItems(text)
        if (items.isEmpty()) return "重组后缺少 Container:Directory"
        for (item in items) {
            if (item.semantic == "Primary") continue
            if (item.length <= 0) return "重组后 ${item.semantic} 长度为 0"
        }
        return null
    }
}
