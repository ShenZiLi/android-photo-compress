package com.photocompress.app.core.jpeg

import com.photocompress.app.core.livephoto.LivePhotoContainer
import com.photocompress.app.data.media.LivePhotoDetector

/** 双图 MPF：主图有损重编码，辅助图、间隔和厂商尾部整体原样搬运。 */
object MpfPhotoContainer {
    data class Plan(
        val primaryEnd: Int,
        val auxiliaryStart: Int,
        val auxiliarySize: Long,
        val payload: ByteArray,
    )

    fun plan(bytes: ByteArray): Plan? {
        val headers = JpegSegments.headerSegments(bytes)
        if (headers.count(JpegSegments::isMpf) != 1 || headers.count(JpegSegments::isXmp) > 1) return null
        val payload = JpegSegments.mpfPayloadOf(bytes) ?: return null
        val entries = MpfRewriter.entries(payload) ?: return null
        if (entries.size != 2 || entries[0].offset != 0L) return null
        if (entries.any { ((it.attributes shr 24) and 7L) != 0L || it.size <= 0 ||
            it.dependent1 !in 0..2 || it.dependent2 !in 0..2 }) return null
        val primaryEnd = JpegSegments.imageEnd(bytes) ?: return null
        val base = (JpegSegments.mpfPayloadOffset(bytes) ?: return null).toLong() + 4L
        val auxiliary = base + entries[1].offset
        if (auxiliary < primaryEnd || auxiliary >= bytes.size || entries[0].size > bytes.size ||
            entries[1].size > bytes.size - auxiliary) return null
        // 允许附加图的声明长度包含已有填充，允许主图 size 陈旧；以真实 JPEG 边界为准。
        JpegSegments.imageEnd(bytes, auxiliary.toInt(), (auxiliary + entries[1].size).toInt()) ?: return null
        return Plan(primaryEnd, auxiliary.toInt(), entries[1].size, payload)
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
            val secondary = primary.size.toLong() + plan.auxiliaryStart - plan.primaryEnd
            val patched = MpfRewriter.updateEntries(plan.payload,
                longArrayOf(primary.size.toLong(), plan.auxiliarySize), longArrayOf(0L, secondary), base.toLong())
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
                if (verified.primaryEnd != next.size || verified.auxiliaryStart != secondary.toInt() ||
                    verified.auxiliarySize != plan.auxiliarySize) return null
                return out
            }
            primary = next
        }
        return null
    }
}
