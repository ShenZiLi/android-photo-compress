package com.photocompress.app.core.heif

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import androidx.heifwriter.HeifWriter
import com.photocompress.app.core.xmp.PcXmp
import com.photocompress.app.data.media.QualityTier
import java.io.File

object HeicCompressor {
    const val MAX_FILE_BYTES = HeicContainer.MAX_FILE_BYTES
    const val MAX_PARALLEL_PIXELS = 20_000_000L
    data class Encoded(val quality: Int, val sourceSha: String)
    fun markerOf(file: File): PcXmp.Marker? = HeicContainer.markerOf(file)

    /** 只写调用方的临时文件；编码/重组/校验失败时原片还没有被写入。 */
    fun compress(source: File, encoded: File, result: File, tier: QualityTier,
        marker: PcXmp.Marker, checkCancelled: () -> Unit): Encoded {
        require(source.length() in 1..MAX_FILE_BYTES.toLong()) { "HEIC 文件过大，已保留原片" }
        val sourceBytes = source.readBytes()
        val sourceSha = java.security.MessageDigest.getInstance("SHA-256").digest(sourceBytes)
            .joinToString("") { "%02x".format(it) }
        val original = HeicContainer(sourceBytes)
        checkCancelled()
        // 一张像素缓冲 + 容器重组副本 + UI/编码器余量；解码前拒绝明显不足的堆空间。
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val estimated = original.width.toLong() * original.height * 4 + sourceBytes.size * 6L + 64L * 1024 * 1024
        require(estimated <= available) { "HEIC 可用内存不足，已保留原片；请关闭其他任务后重试" }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })) { "设备无法解码 HEIC，已保留原片" }
        try {
            require(bitmap.width == original.width && bitmap.height == original.height &&
                (Build.VERSION.SDK_INT < 34 || !bitmap.hasGainmap())) { "HEIC 变换或 HDR 无法完整保留，已保留原片" }
            val originalSpace = bitmap.colorSpace
            require(originalSpace != null && (originalSpace.isSrgb || originalSpace == ColorSpace.get(ColorSpace.Named.DISPLAY_P3))) { "HEIC 色彩空间暂不处理，已保留原片" }
            // 保存与原校验完全相同坐标的少量像素，编码后不再持有完整原图。
            val stepX = maxOf(1, original.width / 96)
            val stepY = maxOf(1, original.height / 96)
            val columns = (original.width - 1) / stepX + 1
            val rows = (original.height - 1) / stepY + 1
            val referencePixels = IntArray(columns * rows)
            var referenceIndex = 0
            for (y in 0 until original.height step stepY) {
                checkCancelled()
                for (x in 0 until original.width step stepX) referencePixels[referenceIndex++] = bitmap.getPixel(x, y)
            }
            // 只重标临时 bitmap，不转换 RGB 数值；重组时保留原 ICC/nclx，读回验证色彩与像素。
            bitmap.setColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            // HEVC CQ 与 JPEG quality 的数值不等价，按相同 UI 档位分别标定。
            val quality = when (tier) {
                QualityTier.HIGH -> 75
                QualityTier.BALANCED -> 55
                QualityTier.COMPACT -> 35
            }
            checkCancelled()
            HeifWriter.Builder(encoded.path, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                .setQuality(quality).setMaxImages(1).setGridEnabled(original.grid).build().use { writer ->
                    writer.start(); writer.addBitmap(bitmap); writer.stop(30_000)
                }
            // stop/use 已完成编码并释放编码器，再释放源像素；输出解码不会与它叠加。
            bitmap.recycle()
            checkCancelled()
            val rebuilt = original.rebuild(HeicContainer(encoded.readBytes()), marker)
            result.writeBytes(rebuilt)
            val decoded = requireNotNull(BitmapFactory.decodeFile(result.path)) { "输出 HEIC 无法解码，已保留原片" }
            try {
                require(decoded.width == original.width && decoded.height == original.height && decoded.colorSpace == originalSpace) { "HEIC 尺寸或色彩校验未通过，已保留原片" }
                var squaredError = 0L
                var samples = 0
                var pixelIndex = 0
                for (y in 0 until original.height step stepY) {
                    checkCancelled()
                    for (x in 0 until original.width step stepX) {
                        val a = referencePixels[pixelIndex++]; val b = decoded.getPixel(x, y)
                        for (shift in listOf(0, 8, 16)) {
                            val diff = ((a ushr shift) and 255) - ((b ushr shift) and 255)
                            squaredError += diff.toLong() * diff; samples++
                        }
                    }
                }
                require(squaredError.toDouble() / samples <= 1024) { "HEIC 像素偏差过大，已保留原片" }
            } finally { decoded.recycle() }
            checkCancelled()
            return Encoded(quality, sourceSha)
        } finally { if (!bitmap.isRecycled) bitmap.recycle() }
    }
}
