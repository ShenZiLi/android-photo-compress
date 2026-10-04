package com.photocompress.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 格式判类与跳过原因（D6 / D11 / D12 / C5，对应 AC12 / AC14）。 */
class MediaClassifierTest {

    @Test
    fun `相机原生 JPEG 可处理`() {
        assertEquals(ContainerFormat.JPEG, MediaClassifier.imageFormat("image/jpeg", "IMG_0001.jpg"))
        assertTrue(MediaClassifier.decideImage(ContainerFormat.JPEG, false) is SupportDecision.Supported)
        assertTrue(MediaClassifier.decideImage(ContainerFormat.JPEG, true) is SupportDecision.Supported)
    }

    @Test
    fun `HEIF 因元信息无法保留而跳过`() {
        assertEquals(ContainerFormat.HEIC, MediaClassifier.imageFormat("image/heic", "IMG_0002.heic"))
        val decision = MediaClassifier.decideImage(ContainerFormat.HEIC, false)
        assertTrue(decision is SupportDecision.Skipped)
        assertTrue((decision as SupportDecision.Skipped).reason.contains("HEIF"))
    }

    @Test
    fun `非相机原生静态图一律跳过且原因明确`() {
        val cases = listOf(
            Triple("image/png", "shot.png", ContainerFormat.PNG),
            Triple("image/bmp", "a.bmp", ContainerFormat.BMP),
            Triple("image/webp", "a.webp", ContainerFormat.WEBP),
            Triple("image/gif", "a.gif", ContainerFormat.GIF),
            Triple("image/avif", "a.avif", ContainerFormat.AVIF),
            Triple("image/x-adobe-dng", "a.dng", ContainerFormat.DNG),
        )
        for ((mime, name, expected) in cases) {
            assertEquals(expected, MediaClassifier.imageFormat(mime, name))
            val decision = MediaClassifier.decideImage(expected, false)
            assertTrue("$name 应跳过", decision is SupportDecision.Skipped)
            assertTrue("$name 应有原因", (decision as SupportDecision.Skipped).reason.isNotBlank())
        }
    }

    @Test
    fun `未知图片格式跳过`() {
        val format = MediaClassifier.imageFormat("image/x-weird", "a.xyz")
        assertEquals(ContainerFormat.UNKNOWN, format)
        assertTrue(MediaClassifier.decideImage(format, false) is SupportDecision.Skipped)
    }

    @Test
    fun `视频容器识别`() {
        assertEquals(ContainerFormat.MP4, MediaClassifier.videoFormat("video/mp4", "v.mp4"))
        assertEquals(ContainerFormat.MOV, MediaClassifier.videoFormat("video/quicktime", "v.mov"))
        assertEquals(ContainerFormat.MKV, MediaClassifier.videoFormat("video/x-matroska", "v.mkv"))
        assertEquals(ContainerFormat.WEBM, MediaClassifier.videoFormat("video/webm", "v.webm"))
        assertEquals(ContainerFormat.THREE_GP, MediaClassifier.videoFormat("video/3gpp", "v.3gp"))
        assertEquals(ContainerFormat.TS, MediaClassifier.videoFormat("video/mp2t", "v.ts"))
    }

    @Test
    fun `仅 MP4 容器可处理其它容器跳过`() {
        val h264 = VideoProbe(MediaClassifier.CODEC_AVC, 1920, 1080, 3000, false, -1, -1, "")
        assertTrue(MediaClassifier.decideVideo(ContainerFormat.MP4, h264) is SupportDecision.Supported)

        for (format in listOf(ContainerFormat.MOV, ContainerFormat.MKV, ContainerFormat.WEBM, ContainerFormat.THREE_GP, ContainerFormat.TS)) {
            val decision = MediaClassifier.decideVideo(format, h264)
            assertTrue("$format 应跳过", decision is SupportDecision.Skipped)
        }
    }

    @Test
    fun `10-bit HDR 视频跳过（D11）`() {
        val hdr = VideoProbe(MediaClassifier.CODEC_HEVC, 3840, 2160, 5000, true, 16, 6, "")
        val decision = MediaClassifier.decideVideo(ContainerFormat.MP4, hdr)
        assertTrue(decision is SupportDecision.Skipped)
        assertTrue((decision as SupportDecision.Skipped).reason.contains("10-bit"))
    }

    @Test
    fun `无法解析轨道时跳过`() {
        val none = VideoProbe(null, 0, 0, 0, false, -1, -1, "无视频轨道")
        assertTrue(MediaClassifier.decideVideo(ContainerFormat.MP4, none) is SupportDecision.Skipped)
    }

    @Test
    fun `编码展示名`() {
        assertEquals("H.264", MediaClassifier.codecLabel(MediaClassifier.CODEC_AVC))
        assertEquals("HEVC", MediaClassifier.codecLabel(MediaClassifier.CODEC_HEVC))
        assertEquals("AV1", MediaClassifier.codecLabel(MediaClassifier.CODEC_AV1))
        assertEquals("VP9", MediaClassifier.codecLabel(MediaClassifier.CODEC_VP9))
    }
}
