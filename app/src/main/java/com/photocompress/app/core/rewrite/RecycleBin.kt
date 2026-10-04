package com.photocompress.app.core.rewrite

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

internal object FileUtils {

    fun copy(source: File, target: File) {
        target.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, 1 shl 16)
                output.fd.sync()
            }
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            var n = input.read(buf)
            while (n > 0) {
                md.update(buf, 0, n)
                n = input.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 同目录临时文件（同卷，保证后续原地搬移）。 */
    fun tempSibling(target: File): File = File(target.parentFile, "${target.name}.pc.tmp")
}

/**
 * 应用私有回收站（D1 / F11）。
 * 布局：`files/recycle/<uuid>/<原文件名>`；备份相对路径写入账本。
 */
class RecycleBin(private val context: Context) {

    private val root: File get() = File(context.filesDir, "recycle").apply { mkdirs() }

    fun fileOf(relPath: String): File = File(root, relPath)

    /** 备份原文件，返回相对路径。 */
    fun backup(id: String, source: File): String {
        val dir = File(root, id).apply { mkdirs() }
        val target = File(dir, source.name)
        if (target.exists()) target.delete()
        FileUtils.copy(source, target)
        return "$id/${source.name}"
    }

    /** 用备份原地还原。 */
    fun restore(relPath: String, target: File, mtimeMs: Long) {
        val backup = fileOf(relPath)
        require(backup.exists()) { "备份不存在：$relPath" }
        InPlaceRewriter.writeFrom(target, backup, mtimeMs)
    }

    fun delete(relPath: String) {
        val f = fileOf(relPath)
        f.delete()
        f.parentFile?.takeIf { it != root }?.delete()
    }

    /** 清理所有备份目录，返回释放的字节数。 */
    fun purgeAll(): Long {
        var freed = 0L
        root.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                child.walkBottomUp().forEach { f ->
                    if (f.isFile) freed += f.length()
                    f.delete()
                }
            }
            child.delete()
        }
        return freed
    }

    fun totalSize(): Long {
        var total = 0L
        root.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }
}

/**
 * 原地截断写入（design.md §4.1）。
 *
 * 复用同一 inode 写入并 `setLength`，之后恢复 mtime。
 * 阶段 1 实测：FUSE 下 birth time 无法保留，用户可见的时间语义由
 * MediaStore 字段（DATE_TAKEN / DATE_ADDED）保证，见 research/device-capability-report.md。
 */
object InPlaceRewriter {

    /** 把 [source] 的内容原地写入 [target]，并恢复 [mtimeMs]。 */
    fun writeFrom(target: File, source: File, mtimeMs: Long) {
        RandomAccessFile(target, "rw").use { raf ->
            FileInputStream(source).use { input ->
                val buf = ByteArray(1 shl 16)
                var n = input.read(buf)
                while (n > 0) {
                    raf.write(buf, 0, n)
                    n = input.read(buf)
                }
            }
            raf.setLength(source.length())
            raf.fd.sync()
        }
        if (mtimeMs > 0) target.setLastModified(mtimeMs)
    }

    fun writeBytes(target: File, bytes: ByteArray, mtimeMs: Long) {
        RandomAccessFile(target, "rw").use { raf ->
            raf.write(bytes)
            raf.setLength(bytes.size.toLong())
            raf.fd.sync()
        }
        if (mtimeMs > 0) target.setLastModified(mtimeMs)
    }
}
