package com.photocompress.app.core.xmp

import com.photocompress.app.core.livephoto.LivePhotoContainer
import com.photocompress.app.data.media.LivePhotoDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自有标记的最小改动注入、Container 长度就地改写、内嵌尾段形态判定。 */
class PcXmpTest {

    private val marker = PcXmp.Marker("uuid-1", "0.1.0", 1_700_000_000_000L)

    /** 与真机样张一致的 oplus XMP（单 Description，属性 + Container 子元素）。 */
    private val oplusXmp = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core Test.SNAPSHOT">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about=""
                xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                xmlns:OpCamera="http://ns.oplus.com/photos/1.0/camera/"
                xmlns:Container="http://ns.google.com/photos/1.0/container/"
                xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
              hdrgm:Version="1.0"
              GCamera:MotionPhoto="1"
              GCamera:MotionPhotoVersion="1"
              OpCamera:MotionPhotoOwner="oplus"
              OpCamera:VideoLength="5116097">
              <Container:Directory>
                <rdf:Seq>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item
                      Item:Mime="image/jpeg"
                      Item:Semantic="Primary"
                      Item:Length="0"
                      Item:Padding="0"/>
                  </rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item
                      Item:Mime="image/jpeg"
                      Item:Semantic="GainMap"
                      Item:Length="430057"
                      Item:Padding="0"/>
                  </rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item
                      Item:Mime="video/mp4"
                      Item:Semantic="MotionPhoto"
                      Item:Length="8331026"/>
                  </rdf:li>
                </rdf:Seq>
              </Container:Directory>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    // ------------------------------------------------------------ 标记注入

    @Test
    fun `无 XMP 时新建完整包`() {
        val packet = PcXmp.injectAttributes(null, marker)
        assertTrue(packet.contains("x:xmpmeta"))
        assertEquals("uuid-1", PcXmp.read(packet)!!.id)
    }

    @Test
    fun `已有 XMP 时以属性形式注入且不新增 Description`() {
        val merged = PcXmp.injectAttributes(oplusXmp, marker)
        assertEquals("仍是单个 Description", 1, Regex("<rdf:Description").findAll(merged).count())
        assertEquals(1, Regex("<Container:Directory>").findAll(merged).count())
        assertEquals("uuid-1", PcXmp.read(merged)!!.id)
    }

    @Test
    fun `注入不改变 oplus 的其它字段与元素顺序`() {
        val merged = PcXmp.injectAttributes(oplusXmp, marker)
        assertTrue(merged.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(merged.contains("OpCamera:VideoLength=\"5116097\""))
        assertTrue(merged.contains("hdrgm:Version=\"1.0\""))
        // 原文除注入点外的骨架应保持一致（去掉自有属性后与原文相同）
        val stripped = Regex("""\s+photocompress:[A-Za-z]+="[^"]*"""").replace(merged, "")
            .let { Regex("""\s+xmlns:photocompress="[^"]*"""").replace(it, "") }
        assertEquals(oplusXmp, stripped)
    }

    @Test
    fun `重复注入不会叠加自有属性`() {
        val once = PcXmp.injectAttributes(oplusXmp, marker)
        val twice = PcXmp.injectAttributes(once, marker)
        assertEquals(1, Regex("photocompress:PCId=").findAll(twice).count())
    }

    @Test
    fun `read 对空或无标记返回 null`() {
        assertNull(PcXmp.read(null))
        assertNull(PcXmp.read("<x:xmpmeta/>"))
    }

    // ------------------------------------------------------------ 长度就地改写

    @Test
    fun `rewriteItemLengths 只改数字不改结构`() {
        val out = LivePhotoContainer.rewriteItemLengths(
            oplusXmp,
            mapOf("GainMap" to 430057L, "MotionPhoto" to 776375L),
        )
        assertTrue(out.contains("Item:Length=\"776375\""))
        assertFalse("旧长度应消失", out.contains("8331026"))
        assertTrue(out.contains("Item:Semantic=\"GainMap\"\n              Item:Length=\"430057\""))
        // 除数字外的骨架不变
        assertEquals(1, Regex("<rdf:Description").findAll(out).count())
        assertEquals(1, Regex("<Container:Directory>").findAll(out).count())
        assertTrue(out.contains("OpCamera:VideoLength=\"5116097\""))
    }

    @Test
    fun `rewriteItemLengths 结果可被探测器重新解析`() {
        val out = LivePhotoContainer.rewriteItemLengths(
            oplusXmp,
            mapOf("MotionPhoto" to 200L),
        )
        val items = LivePhotoDetector.parseContainerItems(out)
        assertEquals(3, items.size)
        assertEquals("Primary", items[0].semantic)
        assertEquals(0L, items[0].length)
        assertEquals(430057L, items[1].length)
        assertEquals(200L, items[2].length)
    }

    // ------------------------------------------------------------ 内嵌尾段形态

    private fun box(type: String, size: Int): ByteArray {
        val b = ByteArray(size)
        b[0] = ((size ushr 24) and 0xFF).toByte()
        b[1] = ((size ushr 16) and 0xFF).toByte()
        b[2] = ((size ushr 8) and 0xFF).toByte()
        b[3] = (size and 0xFF).toByte()
        type.toByteArray(Charsets.US_ASCII).copyInto(b, 4)
        return b
    }

    @Test
    fun `普通单 MP4 判定为可重编码`() {
        val plain = box("ftyp", 16) + box("moov", 32) + box("free", 8) + box("mdat", 64)
        assertTrue(LivePhotoContainer.isPlainMp4(plain))
    }

    @Test
    fun `尾部带厂商私有块的复合结构判定为不可重编码`() {
        val composite = box("ftyp", 16) + box("moov", 32) + box("mdat", 64) +
            // 私有块：size 合法、type 非可打印 ASCII（真机样张即如此）
            byteArrayOf(0x00, 0x00, 0x00, 0x40, 0x9A.toByte(), 0x99.toByte(), 0x99.toByte(), 0x3F) +
            ByteArray(0x40 - 8)
        assertFalse(LivePhotoContainer.isPlainMp4(composite))
    }

    @Test
    fun `box 长度与实际不符时判定为不可重编码`() {
        val bad = box("ftyp", 16) + box("mdat", 9999)
        assertFalse(LivePhotoContainer.isPlainMp4(bad))
    }

    @Test
    fun `缺少 moov 或 mdat 时不可重编码`() {
        assertFalse(LivePhotoContainer.isPlainMp4(box("ftyp", 16)))
        assertFalse(LivePhotoContainer.isPlainMp4(box("ftyp", 16) + box("mdat", 16)))
    }

    @Test
    fun `标记读取兼容属性与元素两种形式`() {
        val attr = PcXmp.injectAttributes(oplusXmp, marker)
        assertNotNull(PcXmp.read(attr))
        val elem = PcXmp.buildPacket(
            """<rdf:Description rdf:about="" xmlns:photocompress="urn:x"><photocompress:PCId>elem-1</photocompress:PCId></rdf:Description>"""
        )
        assertEquals("elem-1", PcXmp.read(elem)!!.id)
    }
}
