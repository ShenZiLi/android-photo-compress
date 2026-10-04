package com.photocompress.app.core.rewrite

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 原地截断写入、时间恢复与失败回滚（design.md §4.1 / AC6 / AC8）。 */
class InPlaceRewriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `原地写入复用同一文件且恢复 mtime`() {
        val target = temp.newFile("photo.jpg")
        target.writeBytes(ByteArray(10_000) { 1 })
        val originalModified = target.lastModified()

        val source = temp.newFile("new.jpg")
        source.writeBytes(ByteArray(2_500) { 2 })

        Thread.sleep(1100)
        InPlaceRewriter.writeFrom(target, source, originalModified)

        assertEquals(2_500L, target.length())
        assertEquals("mtime 必须恢复为原值", originalModified, target.lastModified())
        assertArrayEquals(ByteArray(2_500) { 2 }, target.readBytes())
    }

    @Test
    fun `写入失败时可用备份完整还原`() {
        val target = temp.newFile("photo.jpg")
        val original = ByteArray(8_000) { 7 }
        target.writeBytes(original)
        val originalModified = target.lastModified()

        // 模拟压缩前的备份（与 RecycleBin 相同的复制方式）
        val backup = temp.newFile("backup.jpg")
        FileUtils.copy(target, backup)
        assertEquals(FileUtils.sha256(target), FileUtils.sha256(backup))

        // 模拟一次“写坏了”的原地替换
        InPlaceRewriter.writeBytes(target, ByteArray(100) { 9 }, originalModified)
        assertEquals(100L, target.length())

        // 回滚：用备份原地写回
        InPlaceRewriter.writeFrom(target, backup, originalModified)
        assertEquals(original.size.toLong(), target.length())
        assertArrayEquals(original, target.readBytes())
        assertEquals("回滚后 mtime 一致", originalModified, target.lastModified())
    }

    @Test
    fun `sha256 对同一内容稳定且不同内容不同`() {
        val a = temp.newFile("a.bin").apply { writeBytes(ByteArray(1024) { 3 }) }
        val b = temp.newFile("b.bin").apply { writeBytes(ByteArray(1024) { 3 }) }
        val c = temp.newFile("c.bin").apply { writeBytes(ByteArray(1024) { 4 }) }
        assertEquals(FileUtils.sha256(a), FileUtils.sha256(b))
        assertFalse(FileUtils.sha256(a) == FileUtils.sha256(c))
    }

    @Test
    fun `tempSibling 与目标同目录同卷`() {
        val target = File(temp.root, "DCIM/IMG_1.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4))
        }
        val tmp = FileUtils.tempSibling(target)
        assertEquals(target.parentFile, tmp.parentFile)
        assertTrue(tmp.name.startsWith(target.name))
    }
}
