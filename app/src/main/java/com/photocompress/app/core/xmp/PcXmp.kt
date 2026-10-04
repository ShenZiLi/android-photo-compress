package com.photocompress.app.core.xmp

/**
 * 自有 XMP 标记（D5 / F7）。
 *
 * 阶段 1 探针证实 androidx ExifInterface 不支持 `XMP-xxx` 形式的写入，
 * 因此这里操作**整包 XMP**（ExifInterface.TAG_XMP）。
 *
 * 写入策略（尽量不改变原 XMP 的文档结构）：
 * - 已有 XMP：把自有字段作为**属性**注入到第一个 `rdf:Description` 上，
 *   不新增 Description、不改变元素顺序。厂商（oplus）的解析器往往是自研的，
 *   改动越接近原文越安全（实机验证发现：新增 Description 会让实况照片无法播放）。
 * - 没有 XMP：新建一个完整包（此时无可保留内容）。
 */
object PcXmp {

    const val NS_PREFIX = "photocompress"
    const val NS_URI = "urn:photocompress:1.0:meta"

    data class Marker(val id: String, val version: String, val timeMs: Long)

    private val attrIdRegex = Regex("""$NS_PREFIX:PCId="([^"]*)"""")
    private val attrVerRegex = Regex("""$NS_PREFIX:PCVersion="([^"]*)"""")
    private val attrTimeRegex = Regex("""$NS_PREFIX:PCTime="(\d+)"""")
    private val elemIdRegex = Regex("<$NS_PREFIX:PCId>([^<]+)</$NS_PREFIX:PCId>")
    private val elemVerRegex = Regex("<$NS_PREFIX:PCVersion>([^<]+)</$NS_PREFIX:PCVersion>")
    private val elemTimeRegex = Regex("<$NS_PREFIX:PCTime>(\\d+)</$NS_PREFIX:PCTime>")

    /** 兼容属性形式与元素形式。 */
    fun read(xmp: String?): Marker? {
        if (xmp.isNullOrBlank()) return null
        val id = attrIdRegex.find(xmp)?.groupValues?.get(1)
            ?: elemIdRegex.find(xmp)?.groupValues?.get(1)
            ?: return null
        val version = attrVerRegex.find(xmp)?.groupValues?.get(1)
            ?: elemVerRegex.find(xmp)?.groupValues?.get(1) ?: ""
        val time = attrTimeRegex.find(xmp)?.groupValues?.get(1)?.toLongOrNull()
            ?: elemTimeRegex.find(xmp)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return Marker(id, version, time)
    }

    /** 把自有标记注入 XMP：优先以属性形式并入第一个 Description，最小化对原文档的改动。 */
    fun injectAttributes(existing: String?, marker: Marker): String {
        if (existing.isNullOrBlank()) return buildPacket(descriptionWithElements(marker))

        val cleaned = stripOwn(existing)
        val descStart = cleaned.indexOf("<rdf:Description")
        if (descStart < 0) return buildPacket(descriptionWithElements(marker))

        val tagEnd = findTagEnd(cleaned, descStart)
        if (tagEnd < 0) return buildPacket(descriptionWithElements(marker))
        // 自闭合 Description（无子元素）也能挂属性
        val insertAt = if (cleaned[tagEnd - 1] == '/') tagEnd - 1 else tagEnd

        val attrs = buildString {
            append(" xmlns:").append(NS_PREFIX).append("=\"").append(NS_URI).append('"')
            append(' ').append(NS_PREFIX).append(":PCId=\"").append(marker.id).append('"')
            append(' ').append(NS_PREFIX).append(":PCVersion=\"").append(marker.version).append('"')
            append(' ').append(NS_PREFIX).append(":PCTime=\"").append(marker.timeMs).append('"')
        }
        return cleaned.substring(0, insertAt) + attrs + cleaned.substring(insertAt)
    }

    private fun descriptionWithElements(marker: Marker): String = buildString {
        append("<rdf:Description rdf:about=\"\" xmlns:$NS_PREFIX=\"$NS_URI\">\n")
        append("  <$NS_PREFIX:PCId>").append(marker.id).append("</$NS_PREFIX:PCId>\n")
        append("  <$NS_PREFIX:PCVersion>").append(marker.version).append("</$NS_PREFIX:PCVersion>\n")
        append("  <$NS_PREFIX:PCTime>").append(marker.timeMs).append("</$NS_PREFIX:PCTime>\n")
        append("</rdf:Description>")
    }

    /** 去掉此前写入的自有属性（保证幂等）。 */
    private fun stripOwn(xmp: String): String {
        var out = Regex("""\s+$NS_PREFIX:[A-Za-z]+="[^"]*"""").replace(xmp, "")
        out = Regex("""\s+xmlns:$NS_PREFIX="[^"]*"""").replace(out, "")
        return out
    }

    /** 对外暴露的「移除自有标记」，供还原时清除文件内痕迹。 */
    fun removeOwn(xmp: String): String {
        var out = stripOwn(xmp)
        // 也可能存在元素形式（新建包时使用）
        out = Regex("<rdf:Description[^>]*xmlns:$NS_PREFIX=[^>]*/>").replace(out, "")
        out = Regex("<rdf:Description[^>]*xmlns:$NS_PREFIX=[^>]*>[\\s\\S]*?</rdf:Description>").replace(out, "")
        return out
    }

    /** 找到开始标签的 `>`，跳过引号内的字符。 */
    private fun findTagEnd(text: String, from: Int): Int {
        var inQuote = false
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '"') inQuote = !inQuote
            else if (c == '>' && !inQuote) return i
            i++
        }
        return -1
    }

    fun buildPacket(descriptionXml: String): String = buildString {
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"PhotoCompress\">\n")
        append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append(descriptionXml).append('\n')
        append("</rdf:RDF>\n")
        append("</x:xmpmeta>")
    }
}
