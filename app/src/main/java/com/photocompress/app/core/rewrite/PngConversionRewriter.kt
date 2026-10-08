package com.photocompress.app.core.rewrite

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import androidx.core.net.toUri
import android.provider.MediaStore
import android.system.Os
import com.photocompress.app.data.media.MediaItem
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant

/** PNG 转换只改后缀；备份先持久化，复用同一 inode/媒体 ID，失败恢复原 PNG。 */
class PngConversionRewriter(private val context: Context) {
    private val recycle = RecycleBin(context)
    private val journal = RecoveryJournal(context)
    data class Prepared(val entry: RecoveryJournal.Entry, val size: Long)

    fun prepare(id: String, item: MediaItem, destination: File, expectedSha: String, checkCancelled: () -> Unit): Prepared {
        val source = File(item.dataPath)
        check(source.isFile && !destination.exists() && source.parentFile?.canonicalPath == destination.parentFile?.canonicalPath) { "JPEG 目标已存在或原片路径变化，未覆盖" }
        check(MediaStoreUpdater.pointsTo(context, item.uri, source.path)) { "PNG 相册条目已变化" }
        val inode = Os.stat(source.path).st_ino
        val mtime = Files.getLastModifiedTime(source.toPath())
        val dates = MediaStoreUpdater.captureDates(context, item.uri)
        val backup = recycle.backupWithDigest(id, source, checkCancelled)
        try {
            check(backup.sha256 == expectedSha && FileUtils.sha256(source) == expectedSha && Files.getLastModifiedTime(source.toPath()) == mtime) { "处理期间原片已变化，未改写" }
            val entry = RecoveryJournal.Entry(
                id = id, path = source.path, backupRelPath = backup.relativePath, sha256 = expectedSha,
                modifiedTime = mtime.toString(), mediaUri = item.uri.toString(),
                dateTakenMs = dates.getAsLong(MediaStore.Images.Media.DATE_TAKEN) ?: 0,
                dateAddedSec = checkNotNull(dates.getAsLong(MediaStore.MediaColumns.DATE_ADDED)),
                dateModifiedSec = checkNotNull(dates.getAsLong(MediaStore.MediaColumns.DATE_MODIFIED)),
                dateTakenWasNull = dates.getAsLong(MediaStore.Images.Media.DATE_TAKEN) == null,
                convertedPath = destination.path, sourceInode = inode,
            )
            journal.begin(entry)
            return Prepared(entry, backup.size)
        } catch (failure: Throwable) {
            // 这里尚未写入原片；有持久入口则保留备份，未知状态不清理。
            if (journal.entries().none { it.id == id } && source.isFile && FileUtils.sha256(source) == backup.sha256) recycle.deleteBackupChecked(backup.relativePath)
            throw failure
        }
    }

    fun writeJpeg(entry: RecoveryJournal.Entry, encoded: File, checkCancelled: () -> Unit) {
        val current = locate(entry)
        check(current.path == entry.path && FileUtils.sha256(current) == entry.sha256) { "PNG 原片已变化，未转换" }
        checkCancelled()
        InPlaceRewriter.writeFrom(current, encoded, 0, checkCancelled)
        check(FileUtils.sha256(current) == FileUtils.sha256(encoded)) { "JPEG 写回校验失败" }
        restoreTime(current, entry)
        checkCancelled()
        val destination = File(checkNotNull(entry.convertedPath))
        MediaStoreUpdater.renameExisting(context, entry.mediaUri.toUri(), current, destination, "image/jpeg")
        restoreTime(destination, entry)
        MediaStoreUpdater.restoreDates(context, entry.mediaUri.toUri(), dates(entry))
    }

    /** 先验证备份，再恢复当前 inode 内容并改回 PNG；任何同步失败保留入口和备份。 */
    suspend fun restoreOriginal(entry: RecoveryJournal.Entry) {
        val current = locate(entry)
        val original = File(entry.path)
        check(current.path == original.path || !original.exists()) { "PNG 原路径已被其他文件占用，未覆盖" }
        val backup = recycle.fileOf(entry.backupRelPath)
        val alreadyOriginal = FileUtils.sha256(current) == entry.sha256
        if (!alreadyOriginal) {
            check(backup.isFile && FileUtils.sha256(backup) == entry.sha256) { "PNG 原始备份校验失败，未覆盖当前照片" }
            val known = journal.entries().firstOrNull { it.id == entry.id }
                ?: error("PNG 恢复入口缺失，未覆盖当前照片")
            if (known.safetyBackupRelPath == null) {
                val safety = recycle.backupWithDigest("SAFE-${java.util.UUID.randomUUID()}", current)
                journal.begin(known.copy(safetyBackupRelPath = safety.relativePath))
            }
            recycle.restore(entry.backupRelPath, current, 0)
        }
        check(FileUtils.sha256(current) == entry.sha256) { "PNG 恢复内容校验失败" }
        restoreTime(current, entry)
        if (current.path != original.path) MediaStoreUpdater.renameExisting(context, entry.mediaUri.toUri(), current, original, "image/png")
        restoreTime(original, entry)
        MediaStoreUpdater.restoreDates(context, entry.mediaUri.toUri(), dates(entry))
        MediaStoreUpdater.refresh(context, entry.mediaUri.toUri(), original.path,
            entry.dateTakenMs, entry.dateAddedSec, entry.dateModifiedSec)
        check(Os.stat(original.path).st_ino == entry.sourceInode) { "PNG 恢复文件身份未通过" }
    }

    fun prepareRestore(id: String, originalPath: String, currentPath: String, backupPath: String, sha: String, ledgerId: String, uri: Uri): RecoveryJournal.Entry {
        val current = File(currentPath)
        check(current.isFile && File(originalPath).parentFile?.canonicalPath == current.parentFile?.canonicalPath && !File(originalPath).exists()) { "PNG 原路径已存在或 JPEG 不存在，未覆盖" }
        check(MediaStoreUpdater.pointsTo(context, uri, current.path)) { "JPEG 相册条目已变化" }
        val backup = recycle.fileOf(backupPath)
        check(backup.isFile && FileUtils.sha256(backup) == sha) { "PNG 原始备份校验失败，未覆盖照片" }
        val dates = MediaStoreUpdater.captureDates(context, uri)
        val safety = recycle.backupWithDigest("SAFE-$id", current)
        val entry = RecoveryJournal.Entry(id, originalPath, backupPath, sha, Files.getLastModifiedTime(current.toPath()).toString(),
            uri.toString(), dates.getAsLong(MediaStore.Images.Media.DATE_TAKEN) ?: 0,
            checkNotNull(dates.getAsLong(MediaStore.MediaColumns.DATE_ADDED)), checkNotNull(dates.getAsLong(MediaStore.MediaColumns.DATE_MODIFIED)),
            safety.relativePath, ledgerId, dates.getAsLong(MediaStore.Images.Media.DATE_TAKEN) == null, currentPath, Os.stat(current.path).st_ino)
        try {
            journal.begin(entry)
        } catch (failure: Throwable) {
            if (journal.entries().none { it.id == id }) recycle.deleteBackupChecked(safety.relativePath)
            throw failure
        }
        return entry
    }

    fun cleanup(entry: RecoveryJournal.Entry) {
        val known = journal.entries().firstOrNull { it.id == entry.id } ?: entry
        val original = File(entry.path)
        check(original.isFile && FileUtils.sha256(original) == entry.sha256) { "PNG 原片未恢复完整，备份继续保护" }
        val backup = recycle.fileOf(entry.backupRelPath)
        if (backup.isFile && FileUtils.sha256(backup) == entry.sha256) {
            recycle.deleteBackupChecked(entry.backupRelPath)
        }
        known.safetyBackupRelPath?.let(recycle::deleteBackupChecked)
        journal.finish(entry.id)
    }

    fun locate(entry: RecoveryJournal.Entry): File {
        val path = MediaStoreUpdater.pathOf(context, entry.mediaUri.toUri())
        val file = File(path)
        // 允许系统竞争时自动生成的同目录唯一名称；inode 必须仍是本事务原文件。
        check(file.isFile && file.parentFile?.canonicalPath == File(entry.path).parentFile?.canonicalPath &&
            Os.stat(file.path).st_ino == entry.sourceInode) { "转换文件身份变化或已丢失，原始备份继续保护" }
        return file
    }

    private fun restoreTime(file: File, entry: RecoveryJournal.Entry) {
        val expected = FileTime.from(Instant.parse(entry.modifiedTime))
        Files.setLastModifiedTime(file.toPath(), expected)
        check(Files.getLastModifiedTime(file.toPath()) == expected) { "PNG 文件修改时间未恢复" }
    }
    private fun dates(entry: RecoveryJournal.Entry) = ContentValues().apply {
        if (entry.dateTakenWasNull) putNull(MediaStore.Images.Media.DATE_TAKEN) else put(MediaStore.Images.Media.DATE_TAKEN, entry.dateTakenMs)
        put(MediaStore.MediaColumns.DATE_ADDED, entry.dateAddedSec)
        put(MediaStore.MediaColumns.DATE_MODIFIED, entry.dateModifiedSec)
    }
}
