package com.photocompress.app.data.ledger

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 压缩账本（design.md §3）。唯一编号同时写入此表与压缩后文件的 XMP 标记（D5）。 */
@Entity(tableName = "compressed_item")
data class CompressedItemEntity(
    @PrimaryKey val id: String,
    val mediaStoreId: Long,
    val dataPath: String,
    val volumeName: String,
    val bucketName: String,
    val displayName: String,
    val mediaKind: String,
    val mimeType: String,
    val containerFormat: String,
    val videoCodec: String?,
    val originalSize: Long,
    val compressedSize: Long,
    val originalSha256: String,
    val originalDateTakenMs: Long,
    val originalDateAddedSec: Long,
    val originalDateModifiedSec: Long,
    val qualityTier: String,
    val codecUsed: String?,
    val compressedAtMs: Long,
    val restoreDeadlineMs: Long,
    /** 应用私有回收站中的原文件相对路径；为 null 表示备份已清理。 */
    val backupRelPath: String?,
    val backupSize: Long,
    /** DONE / RESTORED / PURGED */
    val status: String,
    val failureReason: String? = null,
) {
    val restorable: Boolean get() = status == STATUS_DONE && backupRelPath != null
    val savedBytes: Long get() = originalSize - compressedSize

    companion object {
        const val STATUS_DONE = "DONE"
        const val STATUS_RESTORED = "RESTORED"
        const val STATUS_PURGED = "PURGED"
    }
}

/** 三类媒体的质量档位（F12 / D4）。 */
@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val photoTier: String = "BALANCED",
    val liveTier: String = "BALANCED",
    val videoTier: String = "BALANCED",
)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM compressed_item")
    fun observeAll(): Flow<List<CompressedItemEntity>>

    @Query("SELECT * FROM compressed_item WHERE dataPath = :path LIMIT 1")
    suspend fun findByPath(path: String): CompressedItemEntity?

    @Query("SELECT * FROM compressed_item WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CompressedItemEntity?

    @Query("SELECT * FROM compressed_item WHERE status = 'DONE' AND backupRelPath IS NOT NULL")
    suspend fun findRestorable(): List<CompressedItemEntity>

    @Query("SELECT * FROM compressed_item WHERE status = 'DONE' AND backupRelPath IS NOT NULL AND restoreDeadlineMs < :now")
    suspend fun findExpired(now: Long): List<CompressedItemEntity>

    @Upsert
    suspend fun upsert(item: CompressedItemEntity)

    @Query("DELETE FROM compressed_item WHERE dataPath = :path")
    suspend fun deleteByPath(path: String)

    @Query("UPDATE compressed_item SET status = :status, backupRelPath = NULL, backupSize = 0 WHERE dataPath = :path")
    suspend fun markBackupGone(path: String, status: String)

    @Query("UPDATE compressed_item SET backupRelPath = NULL, backupSize = 0, status = 'PURGED' WHERE status = 'DONE' AND backupRelPath IS NOT NULL")
    suspend fun purgeAllBackups(): Int
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun get(): SettingsEntity?

    @Upsert
    suspend fun upsert(settings: SettingsEntity)
}

@Database(
    entities = [CompressedItemEntity::class, SettingsEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "photo_compress.db",
            ).build().also { instance = it }
        }
    }
}
