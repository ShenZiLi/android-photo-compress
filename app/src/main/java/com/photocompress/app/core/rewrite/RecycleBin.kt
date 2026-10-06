package com.photocompress.app.core.rewrite

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.nio.file.Files
import java.io.IOException

internal object FileUtils {

    fun copy(source: File, target: File) {
        copyWithSha256(source, target, calculateDigest = false)
    }

    /** 一次顺序读取完成耐久复制和摘要；每块检查取消，避免重复完整读取大文件。 */
    fun copyWithSha256(
        source: File,
        target: File,
        checkCancelled: () -> Unit = {},
        calculateDigest: Boolean = true,
    ): String {
        target.parentFile?.mkdirs()
        val digest = if (calculateDigest) MessageDigest.getInstance("SHA-256") else null
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(1 shl 18)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest?.update(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        checkCancelled()
        return digest?.digest()?.joinToString("") { "%02x".format(it) } ?: ""
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

    data class Backup(val relativePath: String, val sha256: String, val size: Long)

    private val root: File get() = File(context.filesDir, "recycle").apply { mkdirs() }

    fun fileOf(relPath: String): File = File(root, relPath)

    /** 备份原文件，返回相对路径。 */
    fun backup(id: String, source: File): String {
        return backupWithDigest(id, source).relativePath
    }

    fun backupWithDigest(id: String, source: File, checkCancelled: () -> Unit = {}): Backup {
        val dir = File(root, id).apply { mkdirs() }
        val target = File(dir, source.name)
        check(!target.exists()) { "备份编号已存在，不能覆盖" }
        return try {
            val digest = FileUtils.copyWithSha256(source, target, checkCancelled)
            check(target.length() == source.length()) { "备份长度不一致" }
            Backup("$id/${source.name}", digest, target.length())
        } catch (failure: Throwable) {
            target.delete()
            dir.delete()
            throw failure
        }
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

    /** 清理账本备份时校验目录边界与删除结果；失败不能清空还原索引。 */
    fun deleteBackupChecked(relPath: String): Long {
        val directory = root.canonicalFile
        val requested = File(directory, relPath).absoluteFile.toPath().normalize()
        val file = requested.toFile().canonicalFile
        require(file != directory && file.toPath().startsWith(directory.toPath())) {
            "备份路径不在回收站内"
        }
        require(requested == file.toPath()) { "备份路径包含符号链接" }
        if (!file.exists()) return 0L
        check(file.isFile) { "备份路径不是文件" }
        val size = file.length()
        if (!file.delete() || file.exists()) throw IOException("无法删除原始备份")
        file.parentFile?.takeIf { it != directory }?.delete()
        return size
    }

    data class PurgeResult(val freedBytes: Long, val failedCount: Int)

    /** 手动清空同时处理无账本的残留；保留删除失败的备份，不跟随符号链接。 */
    fun purgeUntracked(protectedPaths: Set<String>): PurgeResult {
        val directory = root.canonicalFile
        var freed = 0L
        var failed = 0
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                val file = path.toFile()
                if (file == directory) return@forEach
                val relative = directory.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                if (protectedPaths.any { relative == it || relative.startsWith("$it/") }) return@forEach
                if (Files.isSymbolicLink(path)) {
                    failed++
                } else if (file.isDirectory) {
                    // 失败备份所在的非空目录保留；空目录不计入释放体积。
                    file.delete()
                } else {
                    try {
                        freed += deleteBackupChecked(relative)
                    } catch (_: Exception) {
                        failed++
                    }
                }
            }
        }
        return PurgeResult(freed, failed)
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
    fun writeFrom(target: File, source: File, mtimeMs: Long, checkCancelled: () -> Unit = {}) {
        checkCancelled()
        RandomAccessFile(target, "rw").use { raf ->
            FileInputStream(source).use { input ->
                val buf = ByteArray(1 shl 18)
                var n = input.read(buf)
                while (n > 0) {
                    checkCancelled()
                    raf.write(buf, 0, n)
                    n = input.read(buf)
                }
            }
            raf.setLength(source.length())
            raf.fd.sync()
        }
        if (mtimeMs > 0) check(target.setLastModified(mtimeMs)) { "无法恢复文件修改时间" }
        checkCancelled()
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
