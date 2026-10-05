package com.photocompress.app

import android.media.MediaExtractor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photocompress.app.core.mp4.Mp4Metadata
import com.photocompress.app.core.video.VideoTranscoder
import com.photocompress.app.data.media.QualityTier
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * 视频压缩后元数据保留的回归验证。
 *
 * 背景：MediaMuxer 只写编码必需的结构，源文件的相机元数据（`moov/udta`、
 * `moov/meta`、厂商私有 box）会丢失，靠 [Mp4Metadata.inject] 搬回。
 * 本测试用真机上的真实相机视频跑一遍完整链路，逐项比对 moov 子框类型。
 *
 * 执行：
 * ```
 * ./gradlew :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -e class com.photocompress.app.VideoMetadataPreservedTest \
 *   com.photocompress.app.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d -s PCMETAVERIFY
 * ```
 */
@RunWith(AndroidJUnit4::class)
class VideoMetadataPreservedTest {

    private val tag = "PCMETAVERIFY"

    /** 需要保留的 moov 级元数据框（结构性框 mvhd/trak 由产物自身提供，不比对）。 */
    private val metadataTypes = listOf("udta", "meta", "titl")

    @Test
    fun preservesMoovMetadataAfterCompress() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val src = findCameraVideo()
        if (src == null) {
            Log.w(tag, "未找到可用的相机视频样本，跳过")
            return
        }
        Log.i(tag, "源文件: ${src.absolutePath} (${src.length()} 字节)")

        val dst = File(ctx.cacheDir, "pc_meta_verify.mp4")
        runCatching { dst.delete() }

        val res = VideoTranscoder.transcode(src, dst, QualityTier.BALANCED)
        Log.i(tag, "转码: success=${res.success} reason=${res.reason}")
        assertTrue("转码失败：${res.reason}", res.success && dst.exists())

        Mp4Metadata.inject(dst, src)
        logParsableTracks(dst)

        val srcTypes = moovChildTypes(src)
        val dstTypes = moovChildTypes(dst)
        Log.i(tag, "源   moov 子框: $srcTypes")
        Log.i(tag, "产物 moov 子框: $dstTypes")

        // 只比「存在性」不够：产物 moov 自带一个 meta，会把源 meta 顶掉却不缺席。
        // 因此逐字节比对源与产物的同名元数据框，才能判定相机信息是否真的搬过来了。
        val srcBoxes = moovChildBoxMap(src)
        val dstBoxes = moovChildBoxMap(dst)
        val bad = ArrayList<String>()
        for (type in metadataTypes) {
            val srcBytes = srcBoxes[type]
            if (srcBytes == null) {
                Log.i(tag, "  $type: 源无此项")
                continue
            }
            val dstBytes = dstBoxes[type]
            when {
                dstBytes == null -> {
                    Log.i(tag, "  $type: ❌ 丢失（源 ${srcBytes.size}B）")
                    bad += "$type 丢失"
                }
                dstBytes.contentEquals(srcBytes) -> Log.i(tag, "  $type: ✅ 字节一致（${srcBytes.size}B）")
                else -> {
                    Log.i(tag, "  $type: ⚠️ 内容不符（源 ${srcBytes.size}B / 产物 ${dstBytes.size}B）——被产物的同名框顶替")
                    bad += "$type 被顶替"
                }
            }
        }

        assertTrue("压缩后元数据未完整保留: $bad", bad.isEmpty())
    }

    /** 取 moov 的直接子框，按类型存字节内容。 */
    private fun moovChildBoxMap(file: File): Map<String, ByteArray> = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val head = ByteArray(16)
            var i = 0L
            while (i + 8 <= len) {
                raf.seek(i)
                if (raf.read(head, 0, 8) < 8) break
                var size = beInt(head, 0).toLong() and 0xFFFFFFFFL
                val type = String(head, 4, 4, Charsets.US_ASCII)
                if (size == 1L) {
                    if (raf.read(head, 8, 8) < 8) break
                    size = beLong(head, 8)
                } else if (size == 0L) {
                    size = len - i
                }
                if (size < 8 || i + size > len) break
                if (type == "moov") {
                    val out = LinkedHashMap<String, ByteArray>()
                    var j = i + 8
                    val end = i + size
                    while (j + 8 <= end) {
                        raf.seek(j)
                        if (raf.read(head, 0, 8) < 8) break
                        val cs = beInt(head, 0).toLong() and 0xFFFFFFFFL
                        if (cs < 8 || j + cs > end) break
                        val buf = ByteArray(cs.toInt())
                        raf.seek(j)
                        raf.readFully(buf)
                        out[String(head, 4, 4, Charsets.US_ASCII)] = buf
                        j += cs
                    }
                    return@use out
                }
                i += size
            }
            emptyMap()
        }
    }.getOrDefault(emptyMap())

    /** 附加：确认 MediaExtractor 仍能正常解析产物（元数据搬运没破坏结构）。 */
    private fun logParsableTracks(dst: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(dst.absolutePath)
            Log.i(tag, "产物可解析，轨道数=${extractor.trackCount}")
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                Log.i(tag, "  track[$i] mime=${f.getString(android.media.MediaFormat.KEY_MIME)}")
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** 列出 moov 的直接子框类型。 */
    private fun moovChildTypes(file: File): List<String> = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            var i = 0L
            val head = ByteArray(16)
            while (i + 8 <= len) {
                raf.seek(i)
                if (raf.read(head, 0, 8) < 8) break
                var size = beInt(head, 0).toLong() and 0xFFFFFFFFL
                val type = String(head, 4, 4, Charsets.US_ASCII)
                if (size == 1L) {
                    if (raf.read(head, 8, 8) < 8) break
                    size = beLong(head, 8)
                } else if (size == 0L) {
                    size = len - i
                }
                if (size < 8) break
                if (type == "moov") return@use childTypes(raf, i + 8, i + size)
                i += size
            }
            emptyList()
        }
    }.getOrDefault(emptyList())

    private fun childTypes(raf: RandomAccessFile, start: Long, end: Long): List<String> {
        val out = ArrayList<String>()
        var i = start
        val head = ByteArray(8)
        while (i + 8 <= end) {
            raf.seek(i)
            if (raf.read(head, 0, 8) < 8) break
            val size = beInt(head, 0).toLong() and 0xFFFFFFFFL
            if (size < 8 || i + size > end) break
            out += String(head, 4, 4, Charsets.US_ASCII)
            i += size
        }
        return out
    }

    /**
     * 找一个元数据最全的相机视频样本。
     *
     * 优先挑 moov 里带 `meta` / `titl` 的（相机信息主要在这里），
     * 这类样本才能暴露「产物自带 meta 导致源 meta 被跳过」的问题；
     * 都没有时退回体积最小的，至少保证跑得通。
     */
    private fun findCameraVideo(): File? {
        val roots = listOf(
            File("/storage/emulated/0/DCIM"),
            File("/storage/emulated/0/Pictures"),
        )
        val candidates = ArrayList<File>()
        for (root in roots) {
            runCatching {
                root.walkTopDown()
                    .maxDepth(4)
                    .filter { it.isFile && it.extension.equals("mp4", true) && it.name.startsWith("VID") }
                    .forEach { candidates += it }
            }
        }
        val rich = candidates.filter { f ->
            val types = moovChildTypes(f)
            "meta" in types || "titl" in types
        }
        Log.i(tag, "候选 ${candidates.size} 个，其中带 meta/titl 的 ${rich.size} 个")
        return (rich.ifEmpty { candidates }).minByOrNull { it.length() }
    }

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun beLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}
