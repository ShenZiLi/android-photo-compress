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
    )

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
            check(resolver.update(uri, dates, null, null) == 1) { "无法恢复媒体日期" }
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

    /** 通知媒体库某条记录已失效（文件被移出媒体库时使用）。 */
    fun notifyDeleted(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    /**
     * 路径变化后重建媒体库记录（HEIC→JPEG 转换后使用）。
     *
     * ⚠️ MediaProvider 删除行时会**连带删除磁盘文件**，因此调用前必须先物理删除旧文件，
     * 且**绝不能**对「需要保留的文件」调用本方法 —— 原地还原请用 [refresh]。
     */
    suspend fun reindex(context: Context, oldUri: Uri?, newPath: String): Uri? = withContext(Dispatchers.IO) {
        val file = File(newPath)
        val newUri = scanFileForUri(context, newPath)
        if (newUri != null && file.exists()) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.SIZE, file.length()) }
            runCatching { context.contentResolver.update(newUri, values, null, null) }
        }
        if (oldUri != null && oldUri != newUri) {
            runCatching { context.contentResolver.delete(oldUri, null, null) }
        }
        newUri
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
