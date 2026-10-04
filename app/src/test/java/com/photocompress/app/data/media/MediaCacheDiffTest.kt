package com.photocompress.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCacheDiffTest {

    private fun snap(path: String, added: Long = 100, modified: Long = 200) =
        CacheSnapshot(path, added, modified)

    @Test
    fun `新条目需要重建`() {
        val diff = diffCache(
            cached = mapOf("image:1" to snap("/a.jpg")),
            live = mapOf("image:1" to snap("/a.jpg"), "image:2" to snap("/b.jpg")),
        )
        assertEquals(setOf("image:2"), diff.changedKeys)
        assertTrue(diff.removedKeys.isEmpty())
    }

    @Test
    fun `未变化条目不做重建`() {
        val diff = diffCache(
            cached = mapOf("image:1" to snap("/a.jpg"), "video:9" to snap("/v.mp4")),
            live = mapOf("image:1" to snap("/a.jpg"), "video:9" to snap("/v.mp4")),
        )
        assertTrue(diff.changedKeys.isEmpty())
        assertTrue(diff.removedKeys.isEmpty())
    }

    @Test
    fun `修改时间变化需要重建`() {
        val diff = diffCache(
            cached = mapOf("image:1" to snap("/a.jpg", modified = 200)),
            live = mapOf("image:1" to snap("/a.jpg", modified = 300)),
        )
        assertEquals(setOf("image:1"), diff.changedKeys)
    }

    @Test
    fun `路径变化需要重建`() {
        val diff = diffCache(
            cached = mapOf("image:1" to snap("/a.jpg")),
            live = mapOf("image:1" to snap("/renamed.jpg")),
        )
        assertEquals(setOf("image:1"), diff.changedKeys)
    }

    @Test
    fun `消失的条目需要删除`() {
        val diff = diffCache(
            cached = mapOf("image:1" to snap("/a.jpg"), "image:2" to snap("/b.jpg")),
            live = mapOf("image:1" to snap("/a.jpg")),
        )
        assertTrue(diff.changedKeys.isEmpty())
        assertEquals(setOf("image:2"), diff.removedKeys)
    }

    @Test
    fun `空媒体库删除全部缓存`() {
        val diff = diffCache(
            cached = mapOf("image:1" to snap("/a.jpg"), "video:2" to snap("/b.mp4")),
            live = emptyMap(),
        )
        assertTrue(diff.changedKeys.isEmpty())
        assertEquals(setOf("image:1", "video:2"), diff.removedKeys)
    }

    @Test
    fun `空缓存时全部视为新增`() {
        val diff = diffCache(
            cached = emptyMap(),
            live = mapOf("image:1" to snap("/a.jpg"), "video:2" to snap("/b.mp4")),
        )
        assertEquals(setOf("image:1", "video:2"), diff.changedKeys)
        assertTrue(diff.removedKeys.isEmpty())
    }
}
