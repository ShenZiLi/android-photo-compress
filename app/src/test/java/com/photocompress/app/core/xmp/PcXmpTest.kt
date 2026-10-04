package com.photocompress.app.core.xmp

import com.photocompress.app.core.livephoto.LivePhotoContainer
import com.photocompress.app.data.media.LivePhotoDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自有 XMP 标记合并与实况照片 XMP 长度重算（design.md §4.4 / §5）。 */
class PcXmpTest {

    private val marker = PcXmp.Marker("uuid-1", "0.1.0", 1_700_000_000_000L)

    private val oplusXmp = """
        <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about=""
            xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
            xmlns:OpCamera="http://ns.oppo.com/photos/1.0/camera/"
            xmlns:Container="http://ns.google.com/photos/1.0/container/"
            xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
            GCamera:MotionPhoto="1" OpCamera:VideoLength="5116097">
          <Container:Directory>
            <rdf:Seq>
              <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
              <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="GainMap" Item:Length="430057" Item:Padding="0"/></rdf:li>
              <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="8331026" Item:Padding="0"/></rdf:li>
            </rdf:Seq>
          </Container:Directory>
        </rdf:Description>
        </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
    """.trimIndent()

    @Test
    fun `mergeInto 无既有 XMP 时新建完整包`() {
        val packet = PcXmp.mergeInto(null, marker)
        assertTrue(packet.contains("x:xmpmeta"))
        assertTrue(packet.contains("photocompress:PCId"))
        assertNotNull(PcXmp.read(packet))
        assertEquals("uuid-1", PcXmp.read(packet)!!.id)
    }

    @Test
    fun `mergeInto 保留其它命名空间并写入自有字段`() {
        val merged = PcXmp.mergeInto(oplusXmp, marker)
        assertTrue("oplus 字段必须保留", merged.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue("opus 字段必须保留", merged.contains("OpCamera:VideoLength=\"5116097\""))
        assertEquals("uuid-1", PcXmp.read(merged)!!.id)
    }

    @Test
    fun `mergeInto 重复合并不会产生多余的自有描述块`() {
        val once = PcXmp.mergeInto(oplusXmp, marker)
        val twice = PcXmp.mergeInto(once, marker)
        // 用开标签计数：一个描述块只会出现一个 <photocompress:PCId>
        assertEquals(1, Regex("<photocompress:PCId>").findAll(twice).count())
        assertEquals(1, Regex("<rdf:Description[^>]*xmlns:photocompress=").findAll(twice).count())
    }

    @Test
    fun `read 对空或无标记返回 null`() {
        assertNull(PcXmp.read(null))
        assertNull(PcXmp.read("<x:xmpmeta/>"))
    }

    @Test
    fun `rebuildXmp 重算 Container 长度且保留 oplus 私有字段`() {
        val rebuilt = LivePhotoContainer.rebuildXmp(
            originalXmp = oplusXmp,
            hasGainMap = true,
            gainMapLength = 430057,
            gainMapPadding = 0,
            hasMotion = true,
            motionLength = 776375,
            motionPadding = 0,
            marker = marker,
        )
        // 新长度生效
        assertTrue(rebuilt.contains("Item:Semantic=\"MotionPhoto\" Item:Length=\"776375\""))
        assertTrue(rebuilt.contains("Item:Semantic=\"GainMap\" Item:Length=\"430057\""))
        // 旧长度不再出现
        assertTrue(!rebuilt.contains("8331026"))
        // oplus 私有字段原样保留（语义未知，不改写）
        assertTrue(rebuilt.contains("OpCamera:VideoLength=\"5116097\""))
        assertTrue(rebuilt.contains("GCamera:MotionPhoto=\"1\""))
        // 自有标记已写入
        assertEquals("uuid-1", PcXmp.read(rebuilt)!!.id)
    }

    @Test
    fun `rebuildXmp 结果可被探测器重新解析且长度自洽`() {
        val rebuilt = LivePhotoContainer.rebuildXmp(
            originalXmp = oplusXmp,
            hasGainMap = true,
            gainMapLength = 100,
            gainMapPadding = 0,
            hasMotion = true,
            motionLength = 200,
            motionPadding = 0,
            marker = marker,
        )
        val items = LivePhotoDetector.parseContainerItems(rebuilt)
        assertEquals(3, items.size)
        assertEquals("Primary", items[0].semantic)
        assertEquals(0L, items[0].length)
        assertEquals(100L, items[1].length)
        assertEquals(200L, items[2].length)
    }
}
