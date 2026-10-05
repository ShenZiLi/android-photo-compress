package com.photocompress.app.core.diagnostics

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/**
 * 设备能力探针（design.md §8 的 U1~U9 中可在设备上直接判定的部分）。
 *
 * 结论以 `PCPROBE:` 前缀写入 logcat，便于在模拟器 / 真机上抓取落盘为
 * research/device-capability-report.md。探针只读／只写自己的临时目录，
 * 不触碰用户媒体。
 */
object DeviceCapabilityProbe {

    private const val TAG = "PCPROBE"

    private const val MIME_AVC = "video/avc"
    private const val MIME_HEVC = "video/hevc"
    private const val MIME_VP9 = "video/vp9"
    private const val MIME_AV1 = "video/av01"
    private const val MIME_MPEG4 = "video/mp4v-es"

    data class EncoderInfo(
        val name: String,
        val mime: String,
        val hardware: Boolean,
        val bitrateRange: String,
        val sizeRange: String,
        val frameRateRange: String,
    )

    /** U4：枚举视频编码器，判断目标编码是否可用（含是否硬件编码器）。 */
    fun probeEncoders(): List<EncoderInfo> {
        val wanted = setOf(MIME_AVC, MIME_HEVC, MIME_VP9, MIME_AV1, MIME_MPEG4)
        val result = mutableListOf<EncoderInfo>()
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            for (mime in info.supportedTypes) {
                if (mime !in wanted) continue
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                val video = caps?.videoCapabilities
                result += EncoderInfo(
                    name = info.name,
                    mime = mime,
                    hardware = info.isHardwareAccelerated,
                    bitrateRange = video?.bitrateRange?.toString() ?: "-",
                    sizeRange = video?.supportedWidths?.toString() ?: "-",
                    frameRateRange = video?.supportedFrameRates?.toString() ?: "-",
                )
            }
        }
        return result.sortedWith(compareBy({ it.mime }, { !it.hardware }, { it.name }))
    }

    /**
     * U4 补充：HEVC Main10（HDR10）编码能力——决定 10bit HDR 视频能否保真重编码。
     * 无此能力时，HDR 源按用户确认的策略降级为 SDR 压缩。
     */
    fun probeHevcMain10(): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(MIME_HEVC, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(MIME_HEVC) }.getOrNull() ?: continue
            val profiles = caps.profileLevels?.map { it.profile }?.distinct() ?: continue
            val main10 = profiles.any {
                it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            }
            Log.i(TAG, "hevcMain10 encoder=${info.name} hw=${info.isHardwareAccelerated} profiles=$profiles main10=$main10")
            if (main10) return true
        }
        return false
    }

    data class FileWriteProbe(
        val path: String,
        val writable: Boolean,
        val creatable: Boolean,
        val creationTimeSupported: Boolean,
        val creationTimePreservedAfterTruncate: Boolean,
        val note: String,
    )

    /**
     * U1 / U2：能否直接写外部存储中"非本应用创建"的文件；
     * 原地截断写入后文件创建时间（birth time）是否保留。
     */
    fun probeInPlaceWrite(): FileWriteProbe {
        val dir = File("/sdcard/Download")
        val path = File(dir, "pc_probe_inplace.bin")
        var creatable = false
        var creationTimeSupported = false
        var preserved = false
        var note = ""

        try {
            creatable = dir.exists() && dir.canWrite()
            if (creatable) {
                path.writeBytes(ByteArray(64 * 1024) { 7 })
                val before = readCreationTime(path)
                creationTimeSupported = before != null
                Thread.sleep(1200)
                // 原地截断写入
                RandomAccessFile(path, "rw").use { raf ->
                    raf.seek(0)
                    raf.write(ByteArray(16 * 1024) { 9 })
                    raf.setLength(16L * 1024)
                }
                val after = readCreationTime(path)
                preserved = before != null && after != null && before == after
                note = "before=$before after=$after"
            } else {
                note = "目录不可写或不存在：$dir"
            }
        } catch (t: Throwable) {
            note = "异常 ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { path.delete() }
        }

        return FileWriteProbe(
            path = "/sdcard/Download",
            writable = dir.canWrite(),
            creatable = creatable,
            creationTimeSupported = creationTimeSupported,
            creationTimePreservedAfterTruncate = preserved,
            note = note,
        )
    }

    private fun readCreationTime(file: File): Long? = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).creationTime().toMillis()
    }.getOrNull()

    data class JpegMetaProbe(
        val exifRoundTrip: Boolean,
        val xmpStandardRoundTrip: Boolean,
        val xmpCustomNamespaceRoundTrip: Boolean,
        val orientationBefore: Int,
        val orientationAfter: Int,
        val note: String,
    )

    /**
     * U6：JPEG 上 EXIF / XMP（含自有命名空间）能否写入并读回；
     * 也顺带验证 Bitmap 重编码后 EXIF 搬运与方向标记是否可控。
     */
    fun probeJpegMetadata(context: Context): JpegMetaProbe {
        val file = File(context.cacheDir, "pc_probe_meta.jpg")
        var exifOk = false
        var xmpStdOk = false
        var xmpCustomOk = false
        var orientationBefore = ExifInterface.ORIENTATION_UNDEFINED
        var orientationAfter = ExifInterface.ORIENTATION_UNDEFINED
        var note = ""
        try {
            // 造一张最小 JPEG
            val bmp = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.rgb(30, 90, 200))
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()

            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_MAKE, "PCTest")
            exif.setAttribute(ExifInterface.TAG_MODEL, "ProbeModel")
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:04 12:00:00")
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "31/1,12/1,3000/100")
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "121/1,28/1,1200/100")
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            exif.setAttribute("XMP-xmp:Rating", "5")
            exif.setAttribute("XMP-photocompress:PCId", "probe-uuid-1234")
            exif.setAttribute("XMP-photocompress:PCVer", "0.1.0")
            exif.saveAttributes()

            val readBack = ExifInterface(file.absolutePath)
            orientationAfter = readBack.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED
            )
            exifOk = readBack.getAttribute(ExifInterface.TAG_MAKE) == "PCTest" &&
                readBack.getAttribute(ExifInterface.TAG_MODEL) == "ProbeModel" &&
                !readBack.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrBlank()
            xmpStdOk = readBack.getAttribute("XMP-xmp:Rating") == "5"
            xmpCustomOk = readBack.getAttribute("XMP-photocompress:PCId") == "probe-uuid-1234"
            orientationBefore = ExifInterface.ORIENTATION_ROTATE_90
            note = "make=${readBack.getAttribute(ExifInterface.TAG_MAKE)} rating=${readBack.getAttribute("XMP-xmp:Rating")} custom=${readBack.getAttribute("XMP-photocompress:PCId")}"
        } catch (t: Throwable) {
            note = "异常 ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { file.delete() }
        }
        return JpegMetaProbe(
            exifRoundTrip = exifOk,
            xmpStandardRoundTrip = xmpStdOk,
            xmpCustomNamespaceRoundTrip = xmpCustomOk,
            orientationBefore = orientationBefore,
            orientationAfter = orientationAfter,
            note = note,
        )
    }

    /** MP4 / 实况照片所需的 muxer 是否支持 HEIF 输出（U3 的一部分）。 */
    fun probeMuxerHeifSupport(): Boolean = runCatching {
        Class.forName("android.media.MediaMuxer")
        val f = android.media.MediaMuxer.OutputFormat::class.java.getField("MUXER_OUTPUT_HEIF")
        f.getInt(null) != 0
    }.getOrDefault(false)

    data class XmpPacketProbe(
        val rawXmpRoundTrip: Boolean,
        val customNamespaceSurvives: Boolean,
        val note: String,
    )

    /**
     * U6 修正：androidx ExifInterface 对 `XMP-xxx` 形式的 setAttribute 不支持写入，
     * 验证改用整包 XMP（TAG_XMP 字节数组）承载自有命名空间是否可行。
     */
    fun probeXmpPacket(context: Context): XmpPacketProbe {
        val file = File(context.cacheDir, "pc_probe_xmp.jpg")
        var roundTrip = false
        var customOk = false
        var note = ""
        try {
            val bmp = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.rgb(10, 10, 10))
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()

            val packet = buildXmpPacket(
                pcId = "probe-uuid-5678",
                pcVer = "0.1.0",
                extraXml = null,
            )
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_XMP, packet)
            exif.setAttribute(ExifInterface.TAG_MAKE, "PCTest")
            exif.saveAttributes()

            val readBack = ExifInterface(file.absolutePath)
            val read = readBack.getAttributeBytes(ExifInterface.TAG_XMP)
            val text = read?.toString(Charsets.UTF_8)
            roundTrip = !text.isNullOrBlank() && text.contains("x:xmpmeta")
            customOk = text?.contains("probe-uuid-5678") == true
            note = "xmpBytes=${read?.size ?: 0} hasId=${text?.contains("probe-uuid-5678")} make=${readBack.getAttribute(ExifInterface.TAG_MAKE)}"
        } catch (t: Throwable) {
            note = "异常 ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { file.delete() }
        }
        return XmpPacketProbe(roundTrip, customOk, note)
    }

    /** 构造包含自有命名空间 `photocompress:` 的完整 XMP 包。 */
    fun buildXmpPacket(pcId: String, pcVer: String, extraXml: String?): String = buildString {
        append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"PhotoCompress\">\n")
        append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append("<rdf:Description rdf:about=\"\" xmlns:photocompress=\"urn:photocompress:1.0:meta\">\n")
        append("<photocompress:PCId>").append(pcId).append("</photocompress:PCId>\n")
        append("<photocompress:PCVersion>").append(pcVer).append("</photocompress:PCVersion>\n")
        append("<photocompress:PCTime>").append(System.currentTimeMillis()).append("</photocompress:PCTime>\n")
        append("</rdf:Description>\n")
        if (extraXml != null) append(extraXml)
        append("</rdf:RDF>\n")
        append("</x:xmpmeta>\n")
        append("<?xpacket end=\"w\"?>")
    }

    data class MediaStoreTimeProbe(
        val inserted: Boolean,
        val dateAddedPreserved: Boolean,
        val dateTakenPreserved: Boolean,
        val dateModifiedPreserved: Boolean,
        val dateAddedUpdatable: Boolean,
        val note: String,
    )

    /**
     * U2 修正：原地截断写入必然改变文件 ctime（FUSE 下 creationTime 映射到 ctime），
     * 因此创建时间的用户可见语义依赖 MediaStore 的时间字段。验证：
     * 原地改写 + mtime 还原后，MediaStore DATE_ADDED / DATE_TAKEN 是否保持；
     * 以及能否主动 update 还原 DATE_ADDED / DATE_MODIFIED。
     */
    fun probeMediaStoreTime(context: Context): MediaStoreTimeProbe {
        val resolver = context.contentResolver
        val file = File("/sdcard/Pictures/pc_probe_ms.jpg")
        var inserted = false
        var addedOk = false
        var takenOk = false
        var modifiedOk = false
        var updatable = false
        var note = ""
        var uri: android.net.Uri? = null
        try {
            file.parentFile?.mkdirs()
            val bmp = android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.rgb(200, 40, 40))
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()

            val latch = java.util.concurrent.CountDownLatch(1)
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("image/jpeg")
            ) { _, u ->
                uri = u
                latch.countDown()
            }
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            inserted = uri != null

            val projection = arrayOf(
                android.provider.MediaStore.MediaColumns._ID,
                android.provider.MediaStore.MediaColumns.DATE_ADDED,
                android.provider.MediaStore.Images.Media.DATE_TAKEN,
                android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
            )
            fun readRow(u: android.net.Uri): LongArray? {
                resolver.query(u, projection, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        return longArrayOf(
                            c.getLong(1), // date_added
                            c.getLong(2), // date_taken
                            c.getLong(3), // date_modified
                        )
                    }
                }
                return null
            }

            val before = uri?.let { readRow(it) }
            if (before != null) {
                Thread.sleep(1200)
                // 原地截断写入（模拟压缩产物替换）
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(0)
                    raf.write(ByteArray(1024) { 3 })
                    raf.setLength(1024L)
                }
                // 还原 mtime
                val origModified = before[2] * 1000L
                file.setLastModified(origModified)

                val after = readRow(uri!!)
                note = "before=$before after=$after"
                if (after != null) {
                    addedOk = before[0] == after[0]
                    takenOk = before[1] == after[1]
                    modifiedOk = before[2] == after[2]
                }
                // 尝试主动 update 还原
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DATE_ADDED, before[0])
                    put(android.provider.MediaStore.Images.Media.DATE_TAKEN, before[1])
                    put(android.provider.MediaStore.MediaColumns.DATE_MODIFIED, before[2])
                }
                updatable = runCatching { resolver.update(uri!!, values, null, null) >= 0 }.getOrDefault(false)
            }
        } catch (t: Throwable) {
            note = "异常 ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { uri?.let { resolver.delete(it, null, null) } }
            runCatching { file.delete() }
        }
        return MediaStoreTimeProbe(inserted, addedOk, takenOk, modifiedOk, updatable, note)
    }

    /** 编码器分辨率能力细节：决定“保持分辨率”能否成立、缩放回退该选多大。 */
    fun probeEncoderSizes() {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            for (mime in info.supportedTypes) {
                if (!mime.startsWith("video/")) continue
                val vc = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: continue
                val wRange = runCatching { "${vc.supportedWidths.lower}..${vc.supportedWidths.upper}" }.getOrDefault("-")
                val hRange = runCatching { "${vc.supportedHeights.lower}..${vc.supportedHeights.upper}" }.getOrDefault("-")
                val wa = runCatching { vc.widthAlignment }.getOrDefault(-1)
                val ha = runCatching { vc.heightAlignment }.getOrDefault(-1)
                val tests = listOf(
                    1920 to 1440, 1920 to 1088, 1920 to 1080, 1450 to 1088,
                    1280 to 960, 960 to 720, 640 to 480, 512 to 384,
                )
                val supported = tests.filter { (w, h) ->
                    runCatching { vc.isSizeSupported(w, h) }.getOrDefault(false)
                }.joinToString("|") { "${it.first}x${it.second}" }
                // 最大可编码尺寸（按高度上限推算）
                val maxH = runCatching { vc.supportedHeights.upper }.getOrDefault(0)
                val probeMaxW = if (maxH > 0) {
                    runCatching { vc.getSupportedWidthsFor(maxH).upper }.getOrDefault(0)
                } else 0
                Log.i(TAG, "encsize mime=$mime name=${info.name} w=$wRange h=$hRange align=${wa}x${ha} maxAtH=$probeMaxW x $maxH supported=[$supported]")
            }
        }
        // 解码器：确认能解 HEVC 1920x1440
        val dec = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in dec.codecInfos) {
            if (info.isEncoder) continue
            if (info.supportedTypes.any { it.equals("video/hevc", true) }) {
                val vc = runCatching { info.getCapabilitiesForType("video/hevc").videoCapabilities }.getOrNull()
                val ok = runCatching { vc?.isSizeSupported(1920, 1440) }.getOrDefault(false)
                Log.i(TAG, "decsize hevc name=${info.name} hw=${info.isHardwareAccelerated} supports1920x1440=$ok")
            }
        }
    }

    /** 供 logcat 抓取：把结论打成可解析的 JSON 行。 */
    fun runAll(context: Context) {
        val sdk = Build.VERSION.SDK_INT
        Log.i(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=$sdk abi=${Build.SUPPORTED_ABIS.joinToString(",")}")

        probeEncoders().forEach { e ->
            Log.i(TAG, "encoder mime=${e.mime} name=${e.name} hardware=${e.hardware} bitrate=${e.bitrateRange} size=${e.sizeRange}")
        }
        val supported = probeEncoders().map { it.mime }.toSet()
        for (mime in listOf(MIME_AVC, MIME_HEVC, MIME_VP9, MIME_AV1)) {
            Log.i(TAG, "encoderAvailable mime=$mime available=${mime in supported}")
        }

        val fw = probeInPlaceWrite()
        Log.i(TAG, "inplace writable=${fw.writable} creatable=${fw.creatable} birthTimeSupported=${fw.creationTimeSupported} birthTimePreserved=${fw.creationTimePreservedAfterTruncate} note=${fw.note}")

        val jm = probeJpegMetadata(context)
        Log.i(TAG, "jpegmeta exif=${jm.exifRoundTrip} xmpStd=${jm.xmpStandardRoundTrip} xmpCustom=${jm.xmpCustomNamespaceRoundTrip} orientationBefore=${jm.orientationBefore} orientationAfter=${jm.orientationAfter} note=${jm.note}")

        val xp = probeXmpPacket(context)
        Log.i(TAG, "xmppacket rawRoundTrip=${xp.rawXmpRoundTrip} customNamespace=${xp.customNamespaceSurvives} note=${xp.note}")

        val ms = probeMediaStoreTime(context)
        Log.i(TAG, "mediaStoreTime inserted=${ms.inserted} dateAddedPreserved=${ms.dateAddedPreserved} dateTakenPreserved=${ms.dateTakenPreserved} dateModifiedPreserved=${ms.dateModifiedPreserved} updatable=${ms.dateAddedUpdatable} note=${ms.note}")

        val heif = probeMuxerHeifSupport()
        Log.i(TAG, "muxerHeif=${heif}")

        val hevcMain10 = probeHevcMain10()
        Log.i(TAG, "hevcMain10Encodable=$hevcMain10")

        probeEncoderSizes()

        // 能力矩阵摘要：驱动后续阶段取舍
        val avc = MIME_AVC in supported
        val hevc = MIME_HEVC in supported
        val vp9 = MIME_VP9 in supported
        val av1 = MIME_AV1 in supported
        Log.i(TAG, "summary avc=$avc hevc=$hevc vp9=$vp9 av1=$av1 heif=$heif hevcMain10=$hevcMain10")
        Log.i(TAG, String.format(Locale.US, "summary_line %s", buildString {
            append("avc=").append(avc)
            append(" hevc=").append(hevc)
            append(" vp9=").append(vp9)
            append(" av1=").append(av1)
            append(" heif=").append(heif)
            append(" hevcMain10=").append(hevcMain10)
        }))
    }
}
