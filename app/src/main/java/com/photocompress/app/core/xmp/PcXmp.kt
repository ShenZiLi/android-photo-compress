package com.photocompress.app.core.xmp

/**
 * 自有 XMP 标记（D5 / F7）。
 *
 * 阶段 1 探针证实 androidx ExifInterface 不支持 `XMP-xxx` 形式的写入，
 * 因此这里操作**整包 XMP**（ExifInterface.TAG_XMP），并在合并时保留包内其它命名空间
 * （实况照片的 GCamera / OpCamera / Container 字段必须原样留下）。
 */
object PcXmp {

    const val NS_PREFIX = "photocompress"
    const val NS_URI = "urn:photocompress:1.0:meta"

    data class Marker(val id: String, val version: String, val timeMs: Long)

    private val pcIdRegex = Regex("<$NS_PREFIX:PCId>([^<]+)</$NS_PREFIX:PCId>")
    private val pcVerRegex = Regex("<$NS_PREFIX:PCVersion>([^<]+)</$NS_PREFIX:PCVersion>")
    private val pcTimeRegex = Regex("<$NS_PREFIX:PCTime>(\\d+)</$NS_PREFIX:PCTime>")

    fun read(xmp: String?): Marker? {
        if (xmp.isNullOrBlank()) return null
        val id = pcIdRegex.find(xmp)?.groupValues?.get(1) ?: return null
        return Marker(
            id = id,
            version = pcVerRegex.find(xmp)?.groupValues?.get(1) ?: "",
            timeMs = pcTimeRegex.find(xmp)?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
        )
    }

    /** 把自有标记合并进既有 XMP 包；无包则新建一个完整包。 */
    fun mergeInto(existing: String?, marker: Marker): String {
        val desc = buildString {
            append("<rdf:Description rdf:about=\"\" xmlns:$NS_PREFIX=\"$NS_URI\">\n")
            append("  <$NS_PREFIX:PCId>").append(marker.id).append("</$NS_PREFIX:PCId>\n")
            append("  <$NS_PREFIX:PCVersion>").append(marker.version).append("</$NS_PREFIX:PCVersion>\n")
            append("  <$NS_PREFIX:PCTime>").append(marker.timeMs).append("</$NS_PREFIX:PCTime>\n")
            append("</rdf:Description>")
        }

        if (existing.isNullOrBlank()) return buildPacket(desc)

        var base = existing
        // 移除旧的 photocompress 描述块，避免重复
        base = Regex("<rdf:Description[^>]*xmlns:$NS_PREFIX=[^>]*>[\\s\\S]*?</rdf:Description>").replace(base, "")
        base = Regex("<rdf:Description[^>]*xmlns:$NS_PREFIX=[^>]*/>").replace(base, "")

        return if (base.contains("</rdf:RDF>")) {
            base.replace("</rdf:RDF>", "$desc\n</rdf:RDF>")
        } else {
            buildPacket(desc)
        }
    }

    fun buildPacket(descriptionXml: String): String = buildString {
        append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"PhotoCompress\">\n")
        append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append(descriptionXml).append('\n')
        append("</rdf:RDF>\n")
        append("</x:xmpmeta>\n")
        append("<?xpacket end=\"w\"?>")
    }
}
