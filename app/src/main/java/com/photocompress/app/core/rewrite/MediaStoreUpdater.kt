package com.photocompress.app.core.rewrite

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import com.photocompress.app.data.media.LivePhotoDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.resume

/** 原地改写后强制扫描同一条目，并核验厂商实况缓存和媒体日期。 */
object MediaStoreUpdater {

    /** 格式转换前保存数据库原始日期（含 null），取消时随原文件恢复。 */
    fun captureDates(context: Context, uri: Uri): ContentValues {
        val row = readRow(context, uri)
        return ContentValues().apply {
            fun keep(column: String, value: Long?) {
                if (value == null) putNull(column) else put(column, value)
            }
            keep(MediaStore.Images.Media.DATE_TAKEN, row.dateTaken)
            keep(MediaStore.MediaColumns.DATE_ADDED, row.dateAdded)
            keep(MediaStore.MediaColumns.DATE_MODIFIED, row.dateModified)
        }
    }

    fun restoreDates(context: Context, uri: Uri, dates: ContentValues) {
        fun matches(row: Row): Boolean = row.dateTaken == dates.getAsLong(MediaStore.Images.Media.DATE_TAKEN) &&
            row.dateAdded == dates.getAsLong(MediaStore.MediaColumns.DATE_ADDED) &&
            row.dateModified == dates.getAsLong(MediaStore.MediaColumns.DATE_MODIFIED)
        if (matches(readRow(context, uri))) return
        context.contentResolver.update(uri, dates, null, null)
        val row = readRow(context, uri)
        check(matches(row)) { "无法恢复媒体日期；文件已保留" }
    }

    private data class Row(
        val id: Long,
        val path: String,
        val size: Long,
        val pending: Int,
        val dateTaken: Long?,
        val dateAdded: Long?,
        val dateModified: Long?,
        val oemVideoSize: Long?,
        val hasOemVideoSize: Boolean,
        val mimeType: String?,
    )

    fun pointsTo(context: Context, uri: Uri, path: String): Boolean =
        runCatching { readRow(context, uri).let { it.path == path && it.pending == 0 } }.getOrDefault(false)

    fun pathOf(context: Context, uri: Uri): String = readRow(context, uri).path

    /** 同目录改名由 MediaProvider 移动同一文件，不删除/新建媒体条目，也不覆盖同名目标。 */
    fun renameExisting(context: Context, uri: Uri, from: File, target: File, mime: String) {
        val before = readRow(context, uri)
        check(before.path == from.path && before.pending == 0 && from.isFile) { "转换媒体身份已变化，未改名" }
        require(from.parentFile?.canonicalPath == target.parentFile?.canonicalPath && !target.exists()) { "转换目标已存在或目录变化，未覆盖" }
        val inode = android.system.Os.stat(from.path).st_ino
        check(context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, target.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
        }, null, null) == 1) { "系统未接受格式转换改名" }
        val after = readRow(context, uri)
        check(after.id == before.id && after.pending == 0 && after.mimeType == mime && after.path == target.path && target.isFile &&
            !from.exists() && android.system.Os.stat(target.path).st_ino == inode) { "转换改名身份或目标路径未通过" }
    }

    private fun readRow(context: Context, uri: Uri): Row =
        checkNotNull(context.contentResolver.query(uri, null, null, null, null)) {
            "无法读取媒体库条目"
        }.use { cursor ->
            check(cursor.moveToFirst()) { "媒体库条目已不存在" }
            fun number(column: String): Long? {
                val index = cursor.getColumnIndex(column)
                return if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
            }
            Row(
                id = checkNotNull(number(MediaStore.MediaColumns._ID)),
                path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)),
                size = number(MediaStore.MediaColumns.SIZE) ?: -1L,
                pending = number(MediaStore.MediaColumns.IS_PENDING)?.toInt() ?: -1,
                dateTaken = number(MediaStore.Images.Media.DATE_TAKEN),
                dateAdded = number(MediaStore.MediaColumns.DATE_ADDED),
                dateModified = number(MediaStore.MediaColumns.DATE_MODIFIED),
                oemVideoSize = number("o_video_size"),
                hasOemVideoSize = cursor.getColumnIndex("o_video_size") >= 0,
                mimeType = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE).takeIf { it >= 0 }?.let { cursor.getString(it) },
            )
        }

    suspend fun refresh(
        context: Context,
        uri: Uri,
        path: String,
        originalDateTakenMs: Long,
        originalDateAddedSec: Long,
        originalDateModifiedSec: Long,
    ) = withContext(Dispatchers.IO) {
        val file = File(path)
        check(file.isFile) { "刷新时原文件不存在" }
        val before = readRow(context, uri)
        check(before.path == path && before.pending == 0) { "媒体路径改变或条目尚未发布" }
        check(before.dateAdded == originalDateAddedSec && before.dateModified == originalDateModifiedSec) {
            "媒体日期已改变，不能覆盖其他操作"
        }
        // 仓库可能用 DATE_ADDED 作为无拍摄日期时的 UI 回退值；保留数据库的原始 null/0。
        check(before.dateTaken == null || before.dateTaken <= 0 || before.dateTaken == originalDateTakenMs) {
            "拍摄日期已改变，不能覆盖其他操作"
        }
        val expectedSize = file.length()
        val expectedMotionSize = LivePhotoDetector.detect(file)?.motionPhotoLength
        val mtime = Files.getLastModifiedTime(file.toPath())
        val resolver = context.contentResolver

        // 已发布条目显式再发布会使 MediaProvider 清除 SIZE/DATE_MODIFIED 扫描缓存。
        // 原地保持 pending=0；不设为 1、不改名、不删除或重建媒体条目。
        check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1) { "媒体库未接受刷新" }

        var after = readRow(context, uri)
        fun checkIdentity() {
            check(after.id == before.id && after.path == path && after.pending == 0) {
                "刷新改变了媒体条目编号、路径或发布状态"
            }
        }
        checkIdentity()
        if (after.dateTaken != before.dateTaken || after.dateAdded != before.dateAdded ||
            after.dateModified != before.dateModified
        ) {
            val dates = ContentValues().apply {
                fun keep(column: String, value: Long?) {
                    if (value == null) putNull(column) else put(column, value)
                }
                keep(MediaStore.Images.Media.DATE_TAKEN, before.dateTaken)
                keep(MediaStore.MediaColumns.DATE_ADDED, before.dateAdded)
                keep(MediaStore.MediaColumns.DATE_MODIFIED, before.dateModified)
            }
            resolver.update(uri, dates, null, null)
            after = readRow(context, uri)
            checkIdentity()
        }
        check(after.size == expectedSize) { "媒体库大小仍为旧值" }
        if (expectedMotionSize != null && before.hasOemVideoSize) {
            check(after.hasOemVideoSize && after.oemVideoSize == expectedMotionSize) {
                "系统相册实况视频长度未同步"
            }
        }
        check(after.dateTaken == before.dateTaken && after.dateAdded == before.dateAdded &&
            after.dateModified == before.dateModified
        ) { "媒体日期恢复校验失败" }
        check(file.length() == expectedSize && Files.getLastModifiedTime(file.toPath()) == mtime) {
            "刷新改变了文件大小或修改时间"
        }
    }

    /** 只扫描现存原片，绝不通过删除旧 URI 重建索引（不同 URI 可能指向同一文件）。 */
    suspend fun scanExisting(context: Context, path: String): Uri = withContext(Dispatchers.IO) {
        val file = File(path)
        check(file.isFile && file.length() > 0) { "待恢复原片不存在" }
        val before = Files.getLastModifiedTime(file.toPath())
        val size = file.length()
        val uri = checkNotNull(scanFileForUri(context, path)) { "原片已保留，相册扫描未完成" }
        val row = readRow(context, uri)
        check(row.path == path && row.pending == 0 && row.size == size) { "原片已保留，相册索引校验失败" }
        check(file.isFile && file.length() == size && Files.getLastModifiedTime(file.toPath()) == before) {
            "扫描改变了原片，恢复备份已保留"
        }
        uri
    }

    private suspend fun scanFileForUri(context: Context, path: String): Uri? =
        suspendCancellableCoroutine { cont ->
            runCatching {
                MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, uri ->
                    if (cont.isActive) cont.resume(uri)
                }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
}
