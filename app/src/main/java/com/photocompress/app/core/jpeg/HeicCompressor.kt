package com.photocompress.app.core.jpeg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * HEIC/HEIF → JPEG 转换。
 *
 * 平台没有公开途径把 EXIF/XMP 写回 HEIF 容器（见能力报告 U3），
 * 因此 HEIC 的压缩方式只能是**格式转换**：解码后用 JPEG 重编码，
 * 再把原图的 EXIF / GPS / XMP 逐项搬到新文件。
 *
 * 代价：文件扩展名由 .heic 变为 .jpg（同目录、同图集），
 * 且厂商私有 EXIF 标签可能无法完全搬运；还原时会按原路径写回 .heic。
 */
object HeicCompressor {

    /** 需要逐项搬运的 EXIF 标签（覆盖相机、曝光、时间、GPS 与描述类）。 */
    private val TAGS = listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_X_RESOLUTION,
        ExifInterface.TAG_Y_RESOLUTION,
        ExifInterface.TAG_RESOLUTION_UNIT,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_SCENE_TYPE,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_SUBJECT_AREA,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
    )

    /** 判定「元信息是否完整搬运」的关键标签：源里有值、输出里也必须读得到。 */
    private val CRITICAL_TAGS = listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_SOFTWARE,
    )

    /**
     * 解码 HEIC 并以 JPEG 写入 [target]，随后搬运元信息。
     *
     * @return null 表示成功；否则返回应跳过的原因（C4：元信息无法完整保留时宁可不转换）。
     */
    fun convert(source: File, target: File, quality: Int): String? {
        val srcExif = runCatching { ExifInterface(source.absolutePath) }.getOrNull()
        // 源里实际存在的关键 EXIF 值，转换后必须逐项存在
        val required = HashMap<String, String>()
        if (srcExif != null) {
            for (tag in CRITICAL_TAGS) {
                srcExif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { required[tag] = it }
            }
        }
        val srcXmp = runCatching { srcExif?.getAttributeBytes(ExifInterface.TAG_XMP) }.getOrNull()

        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
        }
        val bmp = BitmapFactory.decodeFile(source.absolutePath, opts)
            ?: return "HEIC 解码失败（设备缺少对应解码器）"
        try {
            FileOutputStream(target).use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    return "HEIC 重编码失败"
                }
            }
        } finally {
            bmp.recycle()
        }

        copyMetadata(source, target)

        // 校验元信息完整搬运：源里有的关键字段，输出里必须读得到
        val dst = runCatching { ExifInterface(target.absolutePath) }.getOrNull()
            ?: return "输出文件不可读"
        for ((tag, expected) in required) {
            if (dst.getAttribute(tag).isNullOrBlank()) {
                return "HEIC 元信息无法完整搬运（$tag），已放弃压缩"
            }
        }
        if (srcXmp != null && srcXmp.isNotEmpty() && dst.getAttributeBytes(ExifInterface.TAG_XMP) == null) {
            return "HEIC 的 XMP 无法完整搬运，已放弃压缩"
        }
        return null
    }

    /** 把源文件的 EXIF / XMP 搬到目标 JPEG；失败不影响图像本身。 */
    fun copyMetadata(source: File, target: File) {
        runCatching {
            val src = ExifInterface(source.absolutePath)
            val dst = ExifInterface(target.absolutePath)
            for (tag in TAGS) {
                val value = src.getAttribute(tag) ?: continue
                runCatching { dst.setAttribute(tag, value) }
            }
            // XMP 整包搬运（含实况/增益图相关命名空间）
            val xmp = runCatching { src.getAttributeBytes(ExifInterface.TAG_XMP) }.getOrNull()
            if (xmp != null && xmp.isNotEmpty()) {
                runCatching { dst.setAttribute(ExifInterface.TAG_XMP, String(xmp, Charsets.UTF_8)) }
            }
            dst.saveAttributes()
        }
    }

    /** 目标文件名冲突时加序号，避免覆盖已有照片。 */
    fun uniqueSibling(source: File, extensionWithDot: String): File {
        val dir = source.parentFile ?: return source
        val base = source.nameWithoutExtension
        var candidate = File(dir, "$base$extensionWithDot")
        var i = 1
        while (candidate.exists() && candidate.canonicalPath != source.canonicalPath) {
            candidate = File(dir, "$base" + "_$i" + extensionWithDot)
            i++
        }
        return candidate
    }
}
