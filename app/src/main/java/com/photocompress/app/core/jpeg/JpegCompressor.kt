package com.photocompress.app.core.jpeg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.photocompress.app.data.media.QualityTier
import java.io.ByteArrayOutputStream
import java.io.File

/** JPEG 解码 / 按档位重编码（F1 / D4）。 */
object JpegCompressor {

    fun qualityFor(tier: QualityTier): Int = when (tier) {
        QualityTier.HIGH -> 92
        QualityTier.BALANCED -> 85
        QualityTier.COMPACT -> 76
    }

    /** 仅解码原数组 [0, byteCount) 中的主图，保持原尺寸并编码一次。 */
    fun compressJpeg(source: ByteArray, quality: Int, byteCount: Int = source.size): ByteArray? {
        if (byteCount < 0 || byteCount > source.size) return null
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
        }
        val bmp = BitmapFactory.decodeByteArray(source, 0, byteCount, opts) ?: return null
        return try {
            val out = ByteArrayOutputStream(byteCount / 2 + 8192)
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)) null else out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    /** 限定原数组 [0, byteCount) 的解码结果校验：能否解出宽高。 */
    fun probeSize(bytes: ByteArray, byteCount: Int = bytes.size): Pair<Int, Int>? {
        if (byteCount < 0 || byteCount > bytes.size) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, byteCount, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }

    fun probeSize(file: File): Pair<Int, Int>? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }.getOrNull()
}
