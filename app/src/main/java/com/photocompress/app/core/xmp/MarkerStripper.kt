package com.photocompress.app.core.xmp

import com.photocompress.app.core.jpeg.JpegSegments
import com.photocompress.app.core.rewrite.InPlaceRewriter
import java.io.File

/**
 * 清除文件内的自有压缩标记。
 *
 * 用于还原：若备份里也被写入过标记（历史版本或二次压缩导致），
 * 还原后必须把标记清掉，否则该照片会被误判为「已压缩」而无法回到未压缩页。
 */
object MarkerStripper {

    /** 返回是否发生了修改。 */
    fun strip(file: File): Boolean = runCatching {
        if (!file.exists() || file.length() < 16) return false
        val head = ByteArray(2)
        java.io.RandomAccessFile(file, "r").use { it.readFully(head) }
        val isJpeg = (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xFF) == 0xD8
        if (isJpeg) stripJpeg(file) else Mp4XmpMarker.removeTrailing(file)
    }.getOrDefault(false)

    private fun stripJpeg(file: File): Boolean {
        val bytes = file.readBytes()
        if (!JpegSegments.isJpeg(bytes)) return false
        val xmp = JpegSegments.xmpTextOf(bytes) ?: return false
        if (PcXmp.read(xmp) == null) return false
        val cleaned = PcXmp.removeOwn(xmp)
        val rebuilt = JpegSegments.rebuildWithMetadata(bytes, bytes, cleaned)
        if (rebuilt.size == bytes.size && rebuilt.contentEquals(bytes)) return false
        InPlaceRewriter.writeBytes(file, rebuilt, file.lastModified())
        return true
    }
}
