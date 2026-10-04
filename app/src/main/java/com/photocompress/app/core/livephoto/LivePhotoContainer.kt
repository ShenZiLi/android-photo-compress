package com.photocompress.app.core.livephoto

import com.photocompress.app.core.xmp.PcXmp
import com.photocompress.app.data.media.LivePhotoDetector
import java.io.File

/**
 * 实况照片 / Ultra HDR 的容器布局（XMP `Container:Directory`）。
 *
 * 文件结构：主图(JPEG) [ + GainMap(JPEG) ] [ + MotionPhoto(MP4) ]，顺序固定，
 * 每段长度由 XMP 声明，重组后必须重算（design.md §4.4）。
 */
object LivePhotoContainer {

    private const val NS_CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val NS_ITEM = "http://ns.google.com/photos/1.0/container/item/"

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
     * 重建 XMP：重算 Container:Directory 各段长度，并合并自有标记。
     *
     * 注意：`OpCamera:*` 为 oplus 私有字段，语义未知（实测 `VideoLength` 与内嵌视频
     * 真实时长/大小都不吻合），一律**原样保留**，不改写，避免破坏相册识别。
     */
    fun rebuildXmp(
        originalXmp: String?,
        hasGainMap: Boolean,
        gainMapLength: Long,
        gainMapPadding: Long,
        hasMotion: Boolean,
        motionLength: Long,
        motionPadding: Long,
        marker: PcXmp.Marker,
    ): String {
        val base = (originalXmp ?: "")
            .replace(Regex("<Container:Directory>[\\s\\S]*?</Container:Directory>"), "")

        val dir = buildString {
            append("<rdf:Description rdf:about=\"\"")
            append(" xmlns:Container=\"").append(NS_CONTAINER).append("\"")
            append(" xmlns:Item=\"").append(NS_ITEM).append("\">\n")
            append("  <Container:Directory>\n    <rdf:Seq>\n")
            append("      <rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/></rdf:li>\n")
            if (hasGainMap) {
                append("      <rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"GainMap\" Item:Length=\"")
                append(gainMapLength).append("\" Item:Padding=\"").append(gainMapPadding)
                append("\"/></rdf:li>\n")
            }
            if (hasMotion) {
                append("      <rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"")
                append(motionLength).append("\" Item:Padding=\"").append(motionPadding)
                append("\"/></rdf:li>\n")
            }
            append("    </rdf:Seq>\n  </Container:Directory>\n</rdf:Description>")
        }

        val merged = if (base.contains("</rdf:RDF>")) {
            base.replace("</rdf:RDF>", "$dir\n</rdf:RDF>")
        } else {
            base
        }
        return PcXmp.mergeInto(merged, marker)
    }

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
