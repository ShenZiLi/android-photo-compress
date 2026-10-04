package com.photocompress.app.core.rewrite

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 原地改写后的媒体库同步。
 *
 * 阶段 1 探针结论：直接文件写入**不会**自动刷新 MediaStore 行，且
 * DATE_ADDED / DATE_TAKEN / DATE_MODIFIED 三个字段都可写。
 *
 * 因此这里**不触发全量重扫**（重扫会把 DATE_ADDED 重置为扫描时刻），
 * 只做一次精确的字段回写：刷新 SIZE，并把三个时间字段保持为原值（AC4）。
 */
object MediaStoreUpdater {

    suspend fun refresh(
        context: Context,
        uri: Uri,
        path: String,
        originalDateTakenMs: Long,
        originalDateAddedSec: Long,
        originalDateModifiedSec: Long,
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            val file = File(path)
            if (file.exists()) put(MediaStore.MediaColumns.SIZE, file.length())
            put(MediaStore.MediaColumns.DATE_MODIFIED, originalDateModifiedSec)
            put(MediaStore.MediaColumns.DATE_ADDED, originalDateAddedSec)
            if (originalDateTakenMs > 0) {
                put(MediaStore.Images.Media.DATE_TAKEN, originalDateTakenMs)
            }
        }
        // 写两遍：MediaProvider 可能在写入后短暂地按文件 mtime 覆盖一次
        runCatching { context.contentResolver.update(uri, values, null, null) }
        kotlinx.coroutines.delay(120)
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    /** 通知媒体库某条记录已失效（文件被移出媒体库时使用）。 */
    fun notifyDeleted(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
}
