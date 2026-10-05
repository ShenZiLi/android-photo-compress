package com.photocompress.app.core.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.photocompress.app.data.media.QualityTier
import java.io.File
import java.nio.ByteBuffer

/**
 * 视频重编码（F3 / design.md §4.5）：保持分辨率、帧率、时长不变，只调整编码与码率。
 * 音频默认直通；解码→编码走 Surface，避免逐帧拷贝。
 */
object VideoTranscoder {

    private const val TAG = "VideoTranscoder"
    private const val TIMEOUT_US = 10_000L

    data class Result(
        val success: Boolean,
        val outputPath: String? = null,
        val codec: String? = null,
        val reason: String? = null,
    )

    /**
     * 码率档位策略。
     *
     * - [STANDARD]：普通视频页使用，保持既有系数。
     * - [AGGRESSIVE]：实况照片内嵌视频专用（画面占比小、允许一定损失以提高压缩率）。
     */
    enum class BitrateProfile { STANDARD, AGGRESSIVE }

    private data class VideoTrack(
        val index: Int,
        val format: MediaFormat,
        val mime: String,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val bitrate: Int,
        val rotation: Int,
        val durationUs: Long,
    )

    fun transcode(
        input: File,
        output: File,
        tier: QualityTier,
        profile: BitrateProfile = BitrateProfile.STANDARD,
    ): Result {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val video = findVideoTrack(extractor)
                ?: return Result(false, reason = "无视频轨道")
            val target = selectEncoder(video)
                ?: return Result(false, reason = "无可用视频编码器（${video.mime} / ${video.width}x${video.height}）")

            val targetBitrate = chooseBitrate(video, tier, target.mime, profile)
            if (targetBitrate <= 0) return Result(false, reason = "无法确定目标码率")

            val frameRate = video.frameRate.takeIf { it in 1..240 } ?: 30

            val encFormat = MediaFormat.createVideoFormat(target.mime, target.width, target.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                if (video.durationUs > 0) setLong(MediaFormat.KEY_DURATION, video.durationUs)
                // 码率模式：优先 CBR（软件编码器上更接近目标码率）
                val mode = pickBitrateMode(target.mime)
                if (mode >= 0) setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
            }
            Log.i(TAG, "target=${target.name} mime=${target.mime} ${target.width}x${target.height} bitrate=$targetBitrate fps=$frameRate scaled=${target.scaled}")

            encoder = MediaCodec.createEncoderByType(target.mime).apply {
                configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val surface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(video.mime)
            decoder.configure(video.format, surface, null, 0)
            decoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (video.rotation != 0) muxer.setOrientationHint(video.rotation)

            val ok = runPipeline(extractor, video, decoder, encoder, muxer, input)

            if (!ok) {
                return Result(false, reason = "编码管线执行失败")
            }
            if (!output.exists() || output.length() <= 0) {
                return Result(false, reason = "编码输出为空")
            }
            return Result(true, output.absolutePath, MediaClassifierCodec.label(target.mime))
        } catch (t: Throwable) {
            Log.w(TAG, "transcode failed", t)
            return Result(false, reason = "转码异常 ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun runPipeline(
        extractor: MediaExtractor,
        video: VideoTrack,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        input: File,
    ): Boolean {
        val bufferInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()

        // 音频：独立 extractor 直通
        val audioExtractor = MediaExtractor()
        var audioTrackIndex = -1
        var muxAudioTrack = -1
        var audioFormat: MediaFormat? = null
        runCatching {
            audioExtractor.setDataSource(input.absolutePath)
            for (i in 0 until audioExtractor.trackCount) {
                val f = audioExtractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = f
                    break
                }
            }
            if (audioTrackIndex >= 0) audioExtractor.selectTrack(audioTrackIndex)
        }

        var muxerStarted = false
        var videoTrack = -1
        var decoderInputDone = false
        var decoderOutputDone = false
        var encoderOutputDone = false
        var eosSignaled = false

        val audioBuf = ByteBuffer.allocate(256 * 1024)
        val audioInfo = MediaCodec.BufferInfo()

        var guard = 0L
        val maxIterations = 400_000L

        while (!encoderOutputDone && guard++ < maxIterations) {
            // 1) 送解码输入
            if (!decoderInputDone) {
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buf = decoder.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        decoderInputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // 2) 解码输出 → Surface
            if (!decoderOutputDone) {
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outIndex, true)
                    if (eos) decoderOutputDone = true
                }
            }

            // 2b) 解码结束后必须告知编码器输入流结束，否则编码器永不产出 EOS
            if (decoderOutputDone && !eosSignaled) {
                runCatching { encoder.signalEndOfInputStream() }
                eosSignaled = true
            }

            // 3) 排空编码输出
            while (true) {
                val encIndex = encoder.dequeueOutputBuffer(encInfo, 0)
                when {
                    encIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxerStarted = true
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioFormat != null) muxAudioTrack = muxer.addTrack(audioFormat!!)
                        muxer.start()
                        if (audioTrackIndex >= 0) audioBuf.clear()
                    }
                    encIndex >= 0 -> {
                        val encoded = encoder.getOutputBuffer(encIndex)!!
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            encInfo.size = 0
                        }
                        if (encInfo.size > 0 && muxerStarted) {
                            encoded.position(encInfo.offset)
                            encoded.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(videoTrack, encoded, encInfo)
                        }
                        encoder.releaseOutputBuffer(encIndex, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderOutputDone = true
                            break
                        }
                    }
                }
            }

            // 4) 音频直通（视频尚未结束时逐步推进）
            if (audioTrackIndex >= 0 && muxAudioTrack >= 0 && !encoderOutputDone) {
                copyAudioSamples(audioExtractor, muxer, muxAudioTrack, audioBuf, audioInfo)
            }
        }

        // 收尾：把剩余音频写完
        if (audioTrackIndex >= 0 && muxAudioTrack >= 0) {
            copyAllAudio(audioExtractor, muxer, muxAudioTrack, audioBuf, audioInfo)
        }

        runCatching { audioExtractor.release() }
        return muxerStarted && videoTrack >= 0
    }

    private fun copyAudioSamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        track: Int,
        buf: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ) {
        repeat(4) {
            buf.clear()
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) return
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(track, buf, info)
            extractor.advance()
        }
    }

    private fun copyAllAudio(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        track: Int,
        buf: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ) {
        var guard = 0
        while (guard++ < 200_000) {
            buf.clear()
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            runCatching { muxer.writeSampleData(track, buf, info) }
            extractor.advance()
        }
    }

    private fun findVideoTrack(extractor: MediaExtractor): VideoTrack? {
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                extractor.selectTrack(i)
                return VideoTrack(
                    index = i,
                    format = f,
                    mime = mime,
                    width = f.intOr(MediaFormat.KEY_WIDTH, 0),
                    height = f.intOr(MediaFormat.KEY_HEIGHT, 0),
                    frameRate = f.intOr(MediaFormat.KEY_FRAME_RATE, 30),
                    bitrate = f.intOr(MediaFormat.KEY_BIT_RATE, 0),
                    rotation = f.intOr(MediaFormat.KEY_ROTATION, 0),
                    durationUs = f.longOr(MediaFormat.KEY_DURATION, 0L),
                )
            }
        }
        return null
    }

    private data class TargetEnc(
        val mime: String,
        val name: String,
        val width: Int,
        val height: Int,
        /** 编码器不支持源尺寸时按等比缩放后的标记（真机通常不会触发）。 */
        val scaled: Boolean,
    )

    /** 输出容器固定为 MP4；VP9 在部分平台无法封装进 MP4，故不作为输出编码（仅作为源编码可读）。 */
    private val OUTPUT_CODECS = listOf(
        MediaClassifierCodec.HEVC,
        MediaClassifierCodec.AVC,
        MediaClassifierCodec.AV1,
    )

    /** 选择编码器：优先源编码，其次 HEVC → H.264 → AV1；先找能支持原尺寸的，再考虑缩放。 */
    private fun selectEncoder(video: VideoTrack): TargetEnc? {
        val candidates = buildList {
            if (video.mime in OUTPUT_CODECS) add(video.mime)
            addAll(OUTPUT_CODECS)
        }.distinct()
        // 第一轮：必须支持原始分辨率（保持分辨率不变，满足 F3）
        for (mime in candidates) {
            findEncoderSupporting(mime, video.width, video.height, video.frameRate, allowScale = false)
                ?.let { return it }
        }
        // 第二轮：设备编码器上限不足时才等比缩放，取缩放后分辨率最高的方案
        var best: TargetEnc? = null
        for (mime in candidates) {
            val candidate = findEncoderSupporting(mime, video.width, video.height, video.frameRate, allowScale = true)
                ?: continue
            val bestArea = best?.let { it.width.toLong() * it.height } ?: -1L
            if (candidate.width.toLong() * candidate.height > bestArea) best = candidate
        }
        return best
    }

    private fun findEncoderSupporting(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        allowScale: Boolean,
    ): TargetEnc? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder || !info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            val vc = caps.videoCapabilities ?: continue
            val size = fitSize(vc, width, height, fps, allowScale) ?: continue
            return TargetEnc(
                mime = mime,
                name = info.name,
                width = size.first,
                height = size.second,
                scaled = size.first != width || size.second != height,
            )
        }
        return null
    }

    /**
     * 返回编码器可接受的尺寸。
     * [allowScale] = false 时只接受原始尺寸；true 时在**不放大**的前提下按等比
     * 向下搜索可编码的最大尺寸（设备编码器上限不足时才会走到这一步）。
     */
    private fun fitSize(
        vc: MediaCodecInfo.VideoCapabilities,
        w: Int,
        h: Int,
        fps: Int,
        allowScale: Boolean,
    ): Pair<Int, Int>? {
        if (w <= 0 || h <= 0) return null
        val f = fps.coerceIn(1, 240)
        if (runCatching { vc.isSizeSupported(w, h) }.getOrDefault(false)) return w to h
        if (runCatching { vc.areSizeAndRateSupported(w, h, f.toDouble()) }.getOrDefault(false)) return w to h
        if (!allowScale) return null

        val wa = runCatching { vc.widthAlignment }.getOrDefault(2).coerceAtLeast(1)
        val ha = runCatching { vc.heightAlignment }.getOrDefault(2).coerceAtLeast(1)
        if (wa == 0 || ha == 0) return null
        var scale = 0.98
        while (scale >= 0.10) {
            val tw = ((w * scale).toInt() / wa) * wa
            val th = ((h * scale).toInt() / ha) * ha
            if (tw >= wa && th >= ha && runCatching { vc.isSizeSupported(tw, th) }.getOrDefault(false)) {
                return tw to th
            }
            scale -= 0.02
        }
        return null
    }

    private fun chooseBitrate(
        video: VideoTrack,
        tier: QualityTier,
        targetMime: String,
        profile: BitrateProfile = BitrateProfile.STANDARD,
    ): Int {
        val factor = when (profile) {
            BitrateProfile.STANDARD -> when (tier) {
                QualityTier.HIGH -> 0.72
                QualityTier.BALANCED -> 0.50
                QualityTier.COMPACT -> 0.34
            }
            BitrateProfile.AGGRESSIVE -> when (tier) {
                QualityTier.HIGH -> 0.50
                QualityTier.BALANCED -> 0.30
                QualityTier.COMPACT -> 0.18
            }
        }
        val base = if (video.bitrate in 100_000..200_000_000) video.bitrate
        else estimateBitrate(video.width, video.height, video.frameRate)
        var target = (base * factor).toInt()
        if (profile == BitrateProfile.AGGRESSIVE) {
            // 地板：避免极低码率源被压到不可看（约等于「质量参考码率」的 20%）
            val floor = (estimateBitrate(video.width, video.height, video.frameRate) * 0.20).toInt()
            if (target < floor) target = floor
        }
        // 目标编码器码率上限约束
        val caps = encoderBitrateRange(targetMime)
        if (caps != null) target = target.coerceIn(caps.first, caps.second)
        return target
    }

    /** 选择码率模式：优先 CBR，其次 VBR；都不支持返回 -1。 */
    private fun pickBitrateMode(mime: String): Int {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder || !info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            val ec = caps.encoderCapabilities ?: continue
            if (runCatching { ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) }.getOrDefault(false)) {
                return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            }
            if (runCatching { ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) }.getOrDefault(false)) {
                return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            }
        }
        return -1
    }

    private fun encoderBitrateRange(mime: String): Pair<Int, Int>? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder || !info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            val vc = caps.videoCapabilities ?: continue
            return vc.bitrateRange.lower to vc.bitrateRange.upper
        }
        return null
    }

    private fun estimateBitrate(w: Int, h: Int, fps: Int): Int {
        val pixels = w.toLong() * h.toLong()
        val bpp = 0.09
        val fpsSafe = fps.coerceIn(24, 60)
        return (pixels * bpp * fpsSafe).toInt().coerceIn(300_000, 40_000_000)
    }

    private fun MediaFormat.intOr(key: String, def: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(def) else def

    private fun MediaFormat.longOr(key: String, def: Long): Long =
        if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(def) else def
}

/** 编码 MIME 常量与展示名。 */
object MediaClassifierCodec {
    const val AVC = "video/avc"
    const val HEVC = "video/hevc"
    const val AV1 = "video/av01"
    const val VP9 = "video/x-vnd.on2.vp9"

    fun label(mime: String): String = when (mime) {
        AVC -> "H.264"
        HEVC -> "HEVC"
        AV1 -> "AV1"
        VP9 -> "VP9"
        else -> mime
    }

    fun matches(sourceMime: String?, targetMime: String): Boolean =
        sourceMime != null && sourceMime.equals(targetMime, ignoreCase = true)
}
