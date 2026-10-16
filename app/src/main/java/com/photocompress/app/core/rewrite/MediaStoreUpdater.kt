package com.photocompress.app.core.rewrite

import android.content.ContentValues
import android.content.Context
import android.content.ContentUris
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
    fun captureDates(context: Context, uri: Uri): ContentValues = datesOf(readRow(context, uri))

    /** 只读取同路径已发布行或本应用待发布行；缺失不扫描，不在读取日期时发布。 */
    fun captureExistingDates(context: Context, oldUri: Uri, path: String): ContentValues? {
        val uri = resolveRecoveryRow(context, oldUri, path) ?: return null
        val row = readRow(context, uri)
        check(row.path == path) { "读取原始日期时相册路径改变" }
        requireRecoveryRowState(context.packageName, row.pending, row.trashed, row.ownerPackage)
        return datesOf(row)
    }

    private fun datesOf(row: Row): ContentValues {
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
        val differences = restoreDateDifferences(context, uri, dates)
        check(differences.isEmpty()) { "无法恢复相册日期；${differences.joinToString("；")}" }
    }

    private fun dateDifferences(row: Row, dates: ContentValues): List<String> = buildList {
        fun compare(column: String, label: String, actual: Long?, milliseconds: Boolean = false) {
            val expected = dates.getAsLong(column)
            if (actual == expected) return
            android.util.Log.w("MediaStoreUpdater", "$column expected=$expected actual=$actual")
            fun display(value: Long?): String = value?.let {
                val instant = if (milliseconds) java.time.Instant.ofEpochMilli(it) else java.time.Instant.ofEpochSecond(it)
                java.time.format.DateTimeFormatter.ofPattern(if (milliseconds) "yyyy-MM-dd HH:mm:ss.SSS" else "yyyy-MM-dd HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault()).format(instant)
            } ?: "无"
            add("$label：应为${display(expected)}，现为${display(actual)}")
        }
        compare(MediaStore.Images.Media.DATE_TAKEN, "拍摄时间", row.dateTaken, true)
        compare(MediaStore.MediaColumns.DATE_ADDED, "加入相册时间", row.dateAdded)
        compare(MediaStore.MediaColumns.DATE_MODIFIED, "修改时间", row.dateModified)
    }

    private fun restoreDateDifferences(context: Context, uri: Uri, dates: ContentValues,
        keepCurrentDateAdded: Boolean = false): List<String> {
        val before = readRow(context, uri)
        // 仅显式完成入口可接受当前加入相册时间；不修改持久记录中的原始期望值。
        val targets = ContentValues(dates).apply {
            if (keepCurrentDateAdded) put(MediaStore.MediaColumns.DATE_ADDED,
                checkNotNull(before.dateAdded) { "当前加入相册时间不可读，备份继续保护" })
        }
        if (dateDifferences(before, targets).isEmpty()) return emptyList()
        // 权限/索引异常继续抛出；只有成功读回的日期差异才可归为部分恢复。
        context.contentResolver.update(uri, targets, null, null)
        val after = readRow(context, uri)
        check(after.id == before.id && after.path == before.path && after.pending == 0) { "日期同步时相册条目身份已变化" }
        return dateDifferences(after, targets)
    }

    /** 恢复允许旧 URI 已失效；现有已发布行优先，避免反复扫描改变加入相册时间。 */
    suspend fun syncRestored(context: Context, oldUri: Uri, path: String, dates: ContentValues, expectedSha256: String,
        keepCurrentDateAdded: Boolean = false): List<String> =
        withContext(Dispatchers.IO) {
            val file = File(path)
            check(file.isFile && file.length() > 0) { "待恢复原片不存在" }
            val size = file.length()
            val mtime = Files.getLastModifiedTime(file.toPath())
            val inode = android.system.Os.stat(path).st_ino
            fun verifyOriginal() {
                check(file.isFile && file.length() == size && Files.getLastModifiedTime(file.toPath()) == mtime &&
                    android.system.Os.stat(path).st_ino == inode && FileUtils.sha256(file) == expectedSha256) {
                    "原片内容或文件身份已变化，未完成发布；备份继续保护"
                }
            }
            verifyOriginal()
            val uri = resolveRecoveryRow(context, oldUri, path) ?: scanExisting(context, path)
            val before = readRow(context, uri)
            check(before.path == path) { "恢复时相册路径已变化" }
            requireRecoveryRowState(context.packageName, before.pending, before.trashed, before.ownerPackage)
            if (before.pending == 1) {
                check(before.displayName != null && file.name == before.displayName) {
                    "待发布文件名与相册名称不一致，发布可能移动文件；未发布，备份继续保护"
                }
            }
            val motionSize = LivePhotoDetector.detect(file)?.motionPhotoLength
            if (before.pending == 1 || before.size != size || before.dateModified != mtime.toInstant().epochSecond ||
                (motionSize != null && before.hasOemVideoSize && before.oemVideoSize != motionSize)) {
                // 当前行仅作身份基线；恢复后的文件会使派生日期重新计算，随后核对原始日期。
                // 普通压缩的 refresh 日期稳定性要求保持不变。
                verifyOriginal()
                // 仅恢复已校验原片的本应用待发布行；绝不接管其他应用或未知来源的写入。
                check(context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null) == 1) { "相册未接受恢复刷新" }
            }
            val refreshed = readRow(context, uri)
            check(refreshed.id == before.id && refreshed.path == path && refreshed.pending == 0 && refreshed.trashed == 0) {
                "恢复刷新改变了相册条目编号、路径或发布状态"
            }
            val differences = restoreDateDifferences(context, uri, dates, keepCurrentDateAdded)
            val after = readRow(context, uri)
            check(after.id == before.id && after.path == path && after.pending == 0 && after.trashed == 0 && after.size == size) {
                "原片已保留，相册索引校验失败"
            }
            if (motionSize != null && before.hasOemVideoSize) {
                check(after.hasOemVideoSize && after.oemVideoSize == motionSize) { "系统相册实况视频长度未同步" }
            }
            verifyOriginal()
            differences
        }

    /** 待发布内容只可由本应用继续发布；其他来源、未知状态与回收站一律保护。 */
    internal fun requireRecoveryRowState(packageName: String, pending: Int, trashed: Int, ownerPackage: String?) {
        check(trashed == 0) { "相册条目在回收站或状态未知，备份继续保护" }
        check(pending == 0 || (pending == 1 && ownerPackage == packageName)) {
            "相册条目尚未发布且来源不属于本应用，未改动；备份继续保护"
        }
    }

    private fun resolveRecoveryRow(context: Context, oldUri: Uri, path: String): Uri? {
        // 显式包含待发布/回收站行，不能把隐藏的现存行当作不存在后重新扫描。
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL).buildUpon()
            .appendQueryParameter("includePending", "1").appendQueryParameter("includeTrashed", "1").build()
        val id = checkNotNull(context.contentResolver.query(collection,
            arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path), null)) {
            "无法按路径读取相册条目"
        }.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val found = cursor.getLong(0)
                check(!cursor.moveToNext()) { "同路径存在多个相册条目，备份继续保护" }
                found
            }
        } ?: return null
        val resolved = ContentUris.withAppendedId(collection, id)
        val row = readRow(context, resolved)
        check(row.path == path) { "恢复时相册路径已变化" }
        requireRecoveryRowState(context.packageName, row.pending, row.trashed, row.ownerPackage)
        // 旧 URI 仍指向此编号时复用；否则保持已有行，用文件集合 URI 接续。
        return if (oldUri.lastPathSegment?.toLongOrNull() == id && pointsTo(context, oldUri, path)) oldUri else resolved
    }

    private data class Row(
        val id: Long,
        val path: String,
        val size: Long,
        val pending: Int,
        val trashed: Int,
        val ownerPackage: String?,
        val displayName: String?,
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
        check(before.path == from.path && before.pending == 0 && from.isFile) { "转换后相册条目已变化，未改名" }
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
            "无法读取相册条目"
        }.use { cursor ->
            check(cursor.moveToFirst()) { "相册条目已不存在" }
            fun number(column: String): Long? {
                val index = cursor.getColumnIndex(column)
                return if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
            }
            Row(
                id = checkNotNull(number(MediaStore.MediaColumns._ID)),
                path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)),
                size = number(MediaStore.MediaColumns.SIZE) ?: -1L,
                pending = number(MediaStore.MediaColumns.IS_PENDING)?.toInt() ?: -1,
                trashed = number(MediaStore.MediaColumns.IS_TRASHED)?.toInt() ?: -1,
                ownerPackage = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME).takeIf { it >= 0 }
                    ?.let { if (cursor.isNull(it)) null else cursor.getString(it) },
                displayName = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME).takeIf { it >= 0 }
                    ?.let { if (cursor.isNull(it)) null else cursor.getString(it) },
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
        check(before.path == path && before.pending == 0) { "相册路径改变或条目尚未发布" }
        check(before.dateAdded == originalDateAddedSec && before.dateModified == originalDateModifiedSec) {
            "相册日期已改变，不能覆盖其他操作"
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
        }, null, null) == 1) { "相册未接受刷新" }

        var after = readRow(context, uri)
        fun checkIdentity() {
            check(after.id == before.id && after.path == path && after.pending == 0) {
                "刷新改变了相册条目编号、路径或发布状态"
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
        check(after.size == expectedSize) { "相册条目大小仍为旧值" }
        if (expectedMotionSize != null && before.hasOemVideoSize) {
            check(after.hasOemVideoSize && after.oemVideoSize == expectedMotionSize) {
                "系统相册实况视频长度未同步"
            }
        }
        check(after.dateTaken == before.dateTaken && after.dateAdded == before.dateAdded &&
            after.dateModified == before.dateModified
        ) { "相册日期恢复校验失败" }
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
