package com.photocompress.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.photocompress.app.core.compress.CompressionEngine
import com.photocompress.app.data.ledger.AppDatabase
import com.photocompress.app.data.ledger.CompressedItemEntity
import java.util.concurrent.TimeUnit

/**
 * 回收站到期清理（F11 / D1）：保留期（30 天）结束后删除原始文件备份。
 * 只清理备份，已压缩的照片本身不受影响（状态置 PURGED，仍显示为已压缩）。
 */
class RecycleCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dao = AppDatabase.get(applicationContext).ledgerDao()
        val expired = dao.findExpired(System.currentTimeMillis())
        if (expired.isEmpty()) return Result.success()

        val engine = CompressionEngine(applicationContext)
        engine.purgeBackups(expired)
        expired.forEach { dao.markBackupGone(it.dataPath, CompressedItemEntity.STATUS_PURGED) }
        return Result.success()
    }

    companion object {
        private const val PERIODIC_NAME = "recycle_cleanup_periodic"
        private const val IMMEDIATE_NAME = "recycle_cleanup_now"

        /** 每日到期清理 + 启动时立即清理一次。 */
        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            val periodic = PeriodicWorkRequestBuilder<RecycleCleanupWorker>(1, TimeUnit.DAYS).build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
            val immediate = OneTimeWorkRequestBuilder<RecycleCleanupWorker>().build()
            workManager.enqueueUniqueWork(IMMEDIATE_NAME, ExistingWorkPolicy.KEEP, immediate)
        }
    }
}
