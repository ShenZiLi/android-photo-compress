package com.photocompress.app.core.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.photocompress.app.data.media.HdrFidelity
import com.photocompress.app.data.media.MediaClassifier
import com.photocompress.app.data.media.QualityTier
import com.photocompress.app.data.media.VideoProbe
import com.photocompress.app.data.media.VideoProbeRunner
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException

/**
 * 视频重编码（F3 / design.md §4.5）：保持分辨率、帧率、时长不变，只调整编码与码率。
 * 音频默认直通；解码→编码走 Surface，避免逐帧拷贝。
 */
object VideoTranscoder {

    private const val TAG = "VideoTranscoder"
    private const val IDLE_TIMEOUT_US = 10_000L
    private val codecInfos by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.sortedByDescending { it.isHardwareAccelerated }
    }

    data class Result(
        val success: Boolean,
        val outputPath: String? = null,
        val codec: String? = null,
        val reason: String? = null,
        /** HDR 处置标注（保真 / 已转 SDR）；源非 HDR 时为 null。 */
        val hdrNote: String? = null,
    )

    /**
     * 码率档位策略。
     *
     * - [STANDARD]：普通视频页使用，保持既有系数。
     * - [AGGRESSIVE]：实况照片内嵌视频专用（画面占比小、允许一定损失以提高压缩率）。
     */
    enum class BitrateProfile { STANDARD, AGGRESSIVE }

    /**
     * HDR 处置模式。
     *
     * - [DEFAULT]：源非 HDR，保持既有行为（不干预色彩 signaling）。
     * - [PRESERVE]：保留 HDR——HEVC Main10 编码 + 原样色彩 / HDR10 静态元数据。
     *
     * 注：曾有的 [SDR_CLEAR]（把 HDR 源压成 SDR）已按策略删除——HDR 源要么保真，要么跳过。
     */
    enum class HdrMode { DEFAULT, PRESERVE }

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

    /**
     * 转码入口。源为 10bit HDR 时按「保真优先、不可保真即跳过」编排：
     * 1. 设备有 HEVC Main10 编码器 → 尝试 [HdrMode.PRESERVE]，并校验产物确实保留了 HDR；
     * 2. 设备无 Main10 编码器、或保真校验未通过 → **跳过**（返回 `success=false`），
     *    绝不把 HDR 静默转成 SDR —— 用户的硬要求是「保留视频原始信息」。
     *
     * `reason` 会带上 HDR 前缀，由 [CompressionEngine] 直接作为跳过原因展示。
     */
    fun transcode(
        input: File,
        output: File,
        tier: QualityTier,
        profile: BitrateProfile = BitrateProfile.STANDARD,
        checkCancelled: () -> Unit = {},
    ): Result {
        checkCancelled()
        val source = VideoProbeRunner.probe(input.absolutePath)
        if (!source.isHdr) return transcodeOnce(input, output, tier, profile, HdrMode.DEFAULT, checkCancelled)

        val kind = source.hdrKind.label
        if (!hasMain10Encoder()) {
            Log.w(TAG, "设备无 HEVC Main10 编码器，跳过 HDR 源（$kind）")
            return Result(false, reason = "无 HEVC Main10 编码器，无法保真压缩 HDR（$kind），已跳过")
        }

        val preserved = transcodeOnce(input, output, tier, profile, HdrMode.PRESERVE, checkCancelled)
        checkCancelled()
        if (preserved.success && isHdrPreserved(input, output)) {
            return preserved.copy(hdrNote = "HDR 保真（$kind）")
        }
        val why = preserved.reason ?: "输出未保留 HDR 信号"
        Log.w(TAG, "HDR 保真未达成（$why），跳过该视频")
        runCatching { output.delete() }
        // 转码过程失败时把原始失败原因带出来，便于定位（编码器不支持 / 异常等）
        return Result(false, reason = "HDR 保真压缩失败（$why），已跳过")
    }

    private fun transcodeOnce(
        input: File,
        output: File,
        tier: QualityTier,
        profile: BitrateProfile,
        hdrMode: HdrMode,
        checkCancelled: () -> Unit,
    ): Result {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surface: android.view.Surface? = null
        try {
            checkCancelled()
            extractor.setDataSource(input.absolutePath)
            val video = findVideoTrack(extractor)
                ?: return Result(false, reason = "无视频轨道")
            val requireMain10 = hdrMode == HdrMode.PRESERVE
            val target = selectEncoder(video, requireMain10)
                ?: return Result(
                    false,
                    reason = if (requireMain10) {
                        "无支持 HEVC Main10 的视频编码器（${video.width}x${video.height}）"
                    } else {
                        "无可用视频编码器（${video.mime} / ${video.width}x${video.height}）"
                    },
                )

            val targetBitrate = chooseBitrate(video, tier, target, profile, hdr = requireMain10)
            if (targetBitrate <= 0) return Result(false, reason = "无法确定目标码率")

            val frameRate = video.frameRate.takeIf { it in 1..240 } ?: 30

            // 需要在 MediaMuxer.addTrack 前打补丁的色彩/HDR10 元数据（仅保真模式）
            val colorPatch = buildColorPatch(video.format, hdrMode)

            val encFormat = MediaFormat.createVideoFormat(target.mime, target.width, target.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                if (video.durationUs > 0) setLong(MediaFormat.KEY_DURATION, video.durationUs)
                // 码率模式：优先 CBR（软件编码器上更接近目标码率）
                val mode = pickBitrateMode(target)
                if (mode >= 0) setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                // 保真模式：声明 Main10 主档 + 沿用源的色彩 signaling，让编码器产出 10bit HDR 码流
                if (requireMain10) {
                    setInteger(MediaFormat.KEY_PROFILE, main10ProfileOf(video.format))
                    colorPatch?.applyTo(this)
                }
            }
            Log.i(TAG, "target=${target.name} mime=${target.mime} ${target.width}x${target.height} bitrate=$targetBitrate fps=$frameRate hdr=$hdrMode scaled=${target.scaled}")

            encoder = MediaCodec.createByCodecName(target.name).apply {
                configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            surface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(video.mime)
            decoder.configure(video.format, surface, null, 0)
            decoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (video.rotation != 0) muxer.setOrientationHint(video.rotation)

            val ok = runPipeline(extractor, video, decoder, encoder, muxer, input, colorPatch, checkCancelled)
            checkCancelled()

            if (!ok) {
                return Result(false, reason = "编码管线执行失败")
            }
            if (!output.exists() || output.length() <= 0) {
                return Result(false, reason = "编码输出为空")
            }
            return Result(true, output.absolutePath, MediaClassifierCodec.label(target.mime))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "transcode failed", t)
            return Result(false, reason = "转码异常 ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { surface?.release() }
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
        colorPatch: ColorPatch?,
        checkCancelled: () -> Unit,
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

        try {
            var muxerStarted = false
            var videoTrack = -1
            var decoderInputDone = false
            var decoderOutputDone = false
            var encoderOutputDone = false
            var eosSignaled = false

            val audioBuf = ByteBuffer.allocate(256 * 1024)
            val audioInfo = MediaCodec.BufferInfo()

            var lastProgressNs = System.nanoTime()

            while (!encoderOutputDone) {
                checkCancelled()
                var progressed = false
                // 1) 送解码输入
                if (!decoderInputDone) {
                    val inIndex = decoder.dequeueInputBuffer(0)
                    if (inIndex >= 0) {
                        progressed = true
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
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, if (progressed) 0 else IDLE_TIMEOUT_US)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        progressed = true
                        // 部分源把 HDR10 静态元数据只暴露在解码器输出格式里，保真模式下补进 muxer
                        if (colorPatch != null) {
                            runCatching { colorPatch.captureHdrStaticInfo(decoder.outputFormat) }
                        }
                    } else if (outIndex >= 0) {
                        progressed = true
                        val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outIndex, true)
                        if (eos) decoderOutputDone = true
                    }
                }

                // 2b) 解码结束后必须告知编码器输入流结束，否则编码器永不产出 EOS
                if (decoderOutputDone && !eosSignaled) {
                    encoder.signalEndOfInputStream()
                    eosSignaled = true
                    progressed = true
                }

                // 3) 排空编码输出
                while (true) {
                    checkCancelled()
                    // 解码阶段已负责空闲等待；只有解码 EOS 后才等待编码器，避免高频轮询。
                    val encIndex = encoder.dequeueOutputBuffer(encInfo, if (decoderOutputDone && !progressed) IDLE_TIMEOUT_US else 0)
                    when {
                        encIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            progressed = true
                            muxerStarted = true
                            // 把色彩 / HDR10 元数据打进 track format：MediaMuxer 据此写 colr / clli box，
                            // 绕开「厂商编码器 outputFormat 是否回显色彩键」的不确定性
                            val trackFormat = encoder.outputFormat
                            colorPatch?.applyTo(trackFormat)
                            videoTrack = muxer.addTrack(trackFormat)
                            if (audioFormat != null) muxAudioTrack = muxer.addTrack(audioFormat!!)
                            muxer.start()
                            if (audioTrackIndex >= 0) audioBuf.clear()
                        }
                        encIndex >= 0 -> {
                            progressed = true
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
                    copyAudioSamples(audioExtractor, muxer, muxAudioTrack, audioBuf, audioInfo, checkCancelled)
                }
                if (progressed) lastProgressNs = System.nanoTime()
                else check(System.nanoTime() - lastProgressNs < 30_000_000_000L) { "视频编码管线长时间无进展" }
            }

            // 收尾：把剩余音频写完
            if (audioTrackIndex >= 0 && muxAudioTrack >= 0) {
                copyAllAudio(audioExtractor, muxer, muxAudioTrack, audioBuf, audioInfo, checkCancelled)
            }

            return encoderOutputDone && muxerStarted && videoTrack >= 0
        } finally {
            runCatching { audioExtractor.release() }
        }
    }

    private fun copyAudioSamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        track: Int,
        buf: ByteBuffer,
        info: MediaCodec.BufferInfo,
        checkCancelled: () -> Unit,
    ) {
        repeat(4) {
            checkCancelled()
            buf.clear()
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) return
            info.set(0, size, extractor.sampleTime, audioBufferFlags(extractor.sampleFlags))
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
        checkCancelled: () -> Unit,
    ) {
        while (true) {
            checkCancelled()
            buf.clear()
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, audioBufferFlags(extractor.sampleFlags))
            muxer.writeSampleData(track, buf, info)
            extractor.advance()
        }
    }

    /** 提取器和编码器的标志位含义不同，直通只映射同步样本；不支持的样本交给现有失败路径。 */
    private fun audioBufferFlags(sampleFlags: Int): Int {
        require((sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) == 0) { "不支持加密音频直通" }
        require((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) == 0) { "不支持分片音频直通" }
        return if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
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
    private fun selectEncoder(video: VideoTrack, requireMain10: Boolean = false): TargetEnc? {
        val candidates = if (requireMain10) {
            listOf(MediaClassifierCodec.HEVC)
        } else {
            buildList {
                if (video.mime in OUTPUT_CODECS) add(video.mime)
                addAll(OUTPUT_CODECS)
            }.distinct()
        }
        // 第一轮：必须支持原始分辨率（保持分辨率不变，满足 F3）
        for (hardwareOnly in listOf(true, false)) for (mime in candidates) {
            findEncoderSupporting(
                mime, video.width, video.height, video.frameRate,
                allowScale = false, requireMain10 = requireMain10,
                hardwareOnly = hardwareOnly,
            )?.let { return it }
        }
        // HDR 保真不接受降分辨率：宁可交给上层降级为 SDR，也不丢分辨率
        if (requireMain10) return null
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
        requireMain10: Boolean = false,
        hardwareOnly: Boolean = false,
    ): TargetEnc? {
        for (info in codecInfos) {
            if (!info.isEncoder || !info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            if (hardwareOnly && !info.isHardwareAccelerated) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) continue
            if (requireMain10 && caps.profileLevels?.any { it.profile in VideoProbe.MAIN10_PROFILES } != true) continue
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
     * 设备是否存在支持 HEVC Main10（HDR10）档位的编码器。
     * 复用 [MediaClassifier.deviceCanPreserveHdr] 的进程级缓存，与判类阶段结论保持一致。
     */
    private fun hasMain10Encoder(): Boolean = MediaClassifier.deviceCanPreserveHdr

    /** 保真模式下写入编码器的 HEVC profile：沿用源的 Main10 系取值，缺省用 Main10HDR10。 */
    private fun main10ProfileOf(source: MediaFormat): Int {
        val p = source.intOr(MediaFormat.KEY_PROFILE, -1)
        return if (p in VideoProbe.MAIN10_PROFILES) p
        else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
    }

    /**
     * 校验产物是否真的保留了 HDR：先用 MediaExtractor 报告的属性逐项比对，
     * 报告缺省时兜底直读产物落盘的 `colr` box。任一确认即视为保留。
     */
    private fun isHdrPreserved(input: File, output: File): Boolean {
        if (!output.exists() || output.length() <= 0) return false
        val source = VideoProbeRunner.probe(input.absolutePath)
        val produced = VideoProbeRunner.probe(output.absolutePath)
        val (fidelity, reason) = MediaClassifier.compareHdr(source, produced)
        if (fidelity == HdrFidelity.PRESERVED) return true
        val colr = VideoProbeRunner.scanColrInfo(output)
        if (colr.transfer in setOf(16, 18)) {
            Log.i(TAG, "colr box 确认 HDR signaling：transfer=${colr.transfer}")
            return true
        }
        Log.w(TAG, "HDR 保真校验未通过：$reason；产物 colr transfer=${colr.transfer}")
        return false
    }

    /**
     * 需要写入 muxer track format 的色彩 / HDR10 元数据。
     * 在 [MediaMuxer.addTrack] 前打补丁，绕开厂商编码器 outputFormat 是否回显色彩键的不确定性。
     */
    private class ColorPatch(
        private val standard: Int,
        private val transfer: Int,
        private val range: Int,
        hdrStaticInfo: ByteArray? = null,
    ) {
        var hdrStaticInfo: ByteArray? = hdrStaticInfo
            private set

        /** 从解码器输出格式补齐 HDR10 静态元数据（源格式没带时才补）。 */
        fun captureHdrStaticInfo(format: MediaFormat) {
            if (hdrStaticInfo != null) return
            hdrStaticInfo = readHdrStaticInfo(format)
        }

        fun applyTo(format: MediaFormat) {
            if (standard >= 0) runCatching { format.setInteger(MediaFormat.KEY_COLOR_STANDARD, standard) }
            if (transfer >= 0) runCatching { format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, transfer) }
            if (range >= 0) runCatching { format.setInteger(MediaFormat.KEY_COLOR_RANGE, range) }
            hdrStaticInfo?.let { info ->
                runCatching { format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, ByteBuffer.wrap(info)) }
            }
        }
    }

    /**
     * 从源轨道格式提取要保留的色彩信息。
     * 仅 [HdrMode.PRESERVE] 需要；非保真路径不写任何色彩标记（编码器默认即为 SDR，signaling 与内容一致）。
     */
    private fun buildColorPatch(source: MediaFormat, hdrMode: HdrMode): ColorPatch? {
        if (hdrMode != HdrMode.PRESERVE) return null
        val standard = source.intOr(MediaFormat.KEY_COLOR_STANDARD, -1)
        val transfer = source.intOr(MediaFormat.KEY_COLOR_TRANSFER, -1)
        val range = source.intOr(MediaFormat.KEY_COLOR_RANGE, -1)
        val hdrInfo = readHdrStaticInfo(source)
        if (standard < 0 && transfer < 0 && range < 0 && hdrInfo == null) {
            // 源仅靠 profile / 位深判为 HDR、未标注色彩信息：按 BT.2020 + PQ 补齐，保证产物可识别
            return ColorPatch(
                MediaFormat.COLOR_STANDARD_BT2020,
                MediaFormat.COLOR_TRANSFER_ST2084,
                -1,
            )
        }
        return ColorPatch(standard, transfer, range, hdrInfo)
    }

    private fun readHdrStaticInfo(format: MediaFormat): ByteArray? = runCatching {
        if (!format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) return@runCatching null
        val bb = format.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO) ?: return@runCatching null
        val copy = bb.duplicate()
        ByteArray(copy.remaining()).also { copy.get(it) }
    }.getOrNull()

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
        targetEncoder: TargetEnc,
        profile: BitrateProfile = BitrateProfile.STANDARD,
        hdr: Boolean = false,
    ): Int {
        val factor = if (hdr) {
            // 10bit HDR 画面细节与噪声更高，同档位下单独放宽系数（画质优先）
            when (tier) {
                QualityTier.HIGH -> 0.85
                QualityTier.BALANCED -> 0.62
                QualityTier.COMPACT -> 0.45
            }
        } else when (profile) {
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
        if (profile == BitrateProfile.AGGRESSIVE && !hdr) {
            // 地板：避免极低码率源被压到不可看（约等于「质量参考码率」的 20%）
            val floor = (estimateBitrate(video.width, video.height, video.frameRate) * 0.20).toInt()
            if (target < floor) target = floor
        }
        // 目标编码器码率上限约束
        val caps = encoderBitrateRange(targetEncoder)
        if (caps != null) target = target.coerceIn(caps.first, caps.second)
        return target
    }

    /** 选择码率模式：优先 CBR，其次 VBR；都不支持返回 -1。 */
    private fun pickBitrateMode(target: TargetEnc): Int {
        for (info in codecInfos) {
            if (info.name != target.name) continue
            val caps = runCatching { info.getCapabilitiesForType(target.mime) }.getOrNull() ?: continue
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

    private fun encoderBitrateRange(target: TargetEnc): Pair<Int, Int>? {
        for (info in codecInfos) {
            if (info.name != target.name) continue
            val caps = runCatching { info.getCapabilitiesForType(target.mime) }.getOrNull() ?: continue
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
