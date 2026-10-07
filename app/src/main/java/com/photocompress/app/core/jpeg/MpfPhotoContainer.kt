package com.photocompress.app.core.jpeg

import com.photocompress.app.core.livephoto.LivePhotoContainer
import com.photocompress.app.data.media.LivePhotoDetector

/** 多图 MPF：主图有损重编码，所有辅助图、间隔、视频及厂商尾部整体原样搬运。 */
object MpfPhotoContainer {
    data class Auxiliary(val start: Int, val size: Long)

    data class Plan(
        val primaryEnd: Int,
        val auxiliaries: List<Auxiliary>,
        val payload: ByteArray,
    ) {
        val imageCount: Int get() = auxiliaries.size + 1
    }

    fun plan(bytes: ByteArray): Plan? {
        val headers = JpegSegments.headerSegments(bytes)
        if (headers.count(JpegSegments::isMpf) != 1 || headers.count(JpegSegments::isXmp) > 1) return null
        val payload = JpegSegments.mpfPayloadOf(bytes) ?: return null
        val entries = MpfRewriter.entries(payload) ?: return null
        if (entries.size < 2 || entries[0].offset != 0L) return null
        if (entries.any { ((it.attributes shr 24) and 7L) != 0L || it.size <= 0 ||
            it.dependent1 !in 0..entries.size || it.dependent2 !in 0..entries.size }) return null
        val primaryEnd = JpegSegments.imageEnd(bytes) ?: return null
        val base = (JpegSegments.mpfPayloadOffset(bytes) ?: return null).toLong() + 4L
        if (entries[0].size > bytes.size) return null
        val auxiliaries = entries.drop(1).map { entry ->
            val start = base + entry.offset
            if (start < primaryEnd || start >= bytes.size || entry.size > bytes.size - start) return null
            // Original 等条目可内含额外 JPEG/MPF，声明 size 也可包含填充；整体保留。
            JpegSegments.imageEnd(bytes, start.toInt(), (start + entry.size).toInt()) ?: return null
            Auxiliary(start.toInt(), entry.size)
        }
        var previousEnd = primaryEnd.toLong()
        for (auxiliary in auxiliaries.sortedBy { it.start }) {
            if (auxiliary.start < previousEnd) return null
            previousEnd = auxiliary.start + auxiliary.size
        }
        return Plan(primaryEnd, auxiliaries, payload)
    }

    fun rebuild(original: ByteArray, plan: Plan, encodedPrimary: ByteArray, xmp: String): ByteArray? {
        val originalPrimary = original.copyOfRange(0, plan.primaryEnd)
        val hasPrimaryLength = LivePhotoDetector.parseContainerItems(xmp)
            .any { it.semantic == "Primary" && it.length > 0 }
        var primary = JpegSegments.rebuildWithMetadata(encodedPrimary, originalPrimary, xmp)
        repeat(6) {
            val xmpOut = if (hasPrimaryLength) LivePhotoContainer.rewriteItemLengths(xmp,
                mapOf("Primary" to primary.size.toLong())) else xmp
            val base = JpegSegments.mpfPayloadOffset(primary) ?: return null
            val delta = primary.size.toLong() - plan.primaryEnd
            val sizes = longArrayOf(primary.size.toLong()) + plan.auxiliaries.map { it.size }.toLongArray()
            val offsets = longArrayOf(0L) + plan.auxiliaries.map { it.start + delta }.toLongArray()
            val patched = MpfRewriter.updateEntries(plan.payload,
                sizes, offsets, base.toLong())
                ?: return null
            val next = JpegSegments.rebuildWithMetadata(encodedPrimary, originalPrimary, xmpOut, patched)
            if (next.size == primary.size) {
                val suffixSize = original.size - plan.primaryEnd
                val total = next.size.toLong() + suffixSize
                if (total > Int.MAX_VALUE) return null
                val out = ByteArray(total.toInt())
                next.copyInto(out)
                original.copyInto(out, next.size, plan.primaryEnd)
                val verified = MpfPhotoContainer.plan(out) ?: return null
                if (verified.primaryEnd != next.size || verified.imageCount != plan.imageCount ||
                    verified.auxiliaries.indices.any { index ->
                        verified.auxiliaries[index].start.toLong() != offsets[index + 1] ||
                            verified.auxiliaries[index].size != sizes[index + 1]
                    }) return null
                return out
            }
            primary = next
        }
        return null
    }
}
