package com.photocompress.app.data.ledger

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.photocompress.app.data.media.CachedMediaEntity
import com.photocompress.app.data.media.MediaCacheDao
import kotlinx.coroutines.flow.Flow

/** 压缩账本（design.md §3）。唯一编号同时写入此表与压缩后文件的 XMP 标记（D5）。 */
@Entity(tableName = "compressed_item")
data class CompressedItemEntity(
    @PrimaryKey val id: String,
    val mediaStoreId: Long,
    /** 当前媒体文件路径（PNG→JPEG 或旧 HEIC 转换后为新的 .jpg 路径）。 */
    val dataPath: String,
    /** 压缩前的原始路径；与 [dataPath] 不同时表示发生了格式转换，还原需写回该路径。 */
    val originalPath: String = "",
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
    /** 应用私有回收站中的原文件相对路径；null 表示无备份（已清理或 SKIPPED 原片未改写）。 */
    val backupRelPath: String?,
    val backupSize: Long,
    /** DONE / RESTORED / PURGED / SKIPPED（无收益，原片未改写） */
    val status: String,
    val failureReason: String? = null,
) {
    val restorable: Boolean get() = status == STATUS_DONE && backupRelPath != null
    val skipped: Boolean get() = status == STATUS_SKIPPED
    val savedBytes: Long get() = if (skipped) 0L else originalSize - compressedSize

    companion object {
        const val STATUS_DONE = "DONE"
        const val STATUS_RESTORED = "RESTORED"
        const val STATUS_PURGED = "PURGED"
        const val STATUS_SKIPPED = "SKIPPED"
        const val STATUS_FAILED = "FAILED"
    }
}

/** 各媒体的质量档位（F12 / D4）。 */
@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val photoTier: String = "BALANCED",
    /** PNG 原格式压缩开关；关闭时仅识别，不进入压缩候选。 */
    @ColumnInfo(defaultValue = "0") val compressPng: Boolean = false,
    /** 实况照片「图片段」（主图）质量档位。 */
    val liveTier: String = "BALANCED",
    /** 实况照片「视频段」（内嵌视频）质量档位，与普通视频的 [videoTier] 解耦。 */
    val liveVideoTier: String = "BALANCED",
    /** 普通视频质量档位。 */
    val videoTier: String = "BALANCED",
    /** 用户选择排除的图集名，以 `\n` 分隔（这些图集不出现在未压缩/已压缩页）。 */
    val excludedAlbums: String = "",
    /**
     * 上次成功扫描媒体库的时间（秒）。> 0 表示缓存已就绪，
     * 后续启动只需增量扫描，不再做全量扫描。
     */
    val lastScanSec: Long = 0L,
    /**
     * 上次完成扫描时所用的**判类逻辑版本**（见 `CACHE_LOGIC_VERSION`）。
     *
     * 与当前常量不一致时，即使 [lastScanSec] > 0 也必须走一次全量重扫——
     * 否则判类规则调整后，未变化的文件会一直沿用旧的 `skipReason`。
     */
    val cacheLogicVersion: Int = 0,
) {
    val excludedSet: Set<String>
        get() = excludedAlbums.split('\n').filter { it.isNotBlank() }.toSet()
}

@Dao
interface LedgerDao {
    @Query("SELECT * FROM compressed_item")
    fun observeAll(): Flow<List<CompressedItemEntity>>

    @Query("SELECT * FROM compressed_item WHERE dataPath = :path LIMIT 1")
    suspend fun findByPath(path: String): CompressedItemEntity?

    @Query("SELECT * FROM compressed_item WHERE originalPath = :path LIMIT 1")
    suspend fun findByOriginalPath(path: String): CompressedItemEntity?

    @Query("SELECT * FROM compressed_item WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CompressedItemEntity?

    @Query("SELECT * FROM compressed_item WHERE status = 'DONE' AND backupRelPath IS NOT NULL")
    suspend fun findRestorable(): List<CompressedItemEntity>

    @Query("SELECT * FROM compressed_item WHERE backupRelPath IS NOT NULL")
    suspend fun findWithBackups(): List<CompressedItemEntity>

    @Query("SELECT * FROM compressed_item WHERE status = 'DONE' AND backupRelPath IS NOT NULL AND restoreDeadlineMs < :now")
    suspend fun findExpired(now: Long): List<CompressedItemEntity>

    @Upsert
    suspend fun upsert(item: CompressedItemEntity)

    @Query("DELETE FROM compressed_item WHERE dataPath = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM compressed_item WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE compressed_item SET status = 'FAILED', failureReason = '原片内容已恢复，处理未完成' WHERE id = :id")
    suspend fun markFailed(id: String)

    @Query("UPDATE compressed_item SET status = :status, backupRelPath = NULL, backupSize = 0 WHERE dataPath = :path")
    suspend fun markBackupGone(path: String, status: String)

    @Query("UPDATE compressed_item SET backupRelPath = NULL, backupSize = 0, status = CASE WHEN status = 'DONE' THEN 'PURGED' ELSE status END WHERE id IN (:ids) AND backupRelPath IS NOT NULL")
    suspend fun markBackupsGone(ids: List<String>): Int
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(settings: SettingsEntity)

    @Query("UPDATE app_settings SET lastScanSec = :scannedAt, cacheLogicVersion = :logicVersion WHERE id = 1")
    suspend fun markScanCompleted(scannedAt: Long, logicVersion: Int)

    @Query("UPDATE app_settings SET compressPng = :enabled WHERE id = 1")
    suspend fun setCompressPng(enabled: Boolean)

    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun get(): SettingsEntity?

    @Upsert
    suspend fun upsert(settings: SettingsEntity)
}

@Database(
    entities = [CompressedItemEntity::class, SettingsEntity::class, CachedMediaEntity::class],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun settingsDao(): SettingsDao
    abstract fun mediaCacheDao(): MediaCacheDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v1 → v2：新增 originalPath（HEIC 转换还原用）与 excludedAlbums（图集排除）。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE compressed_item ADD COLUMN originalPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN excludedAlbums TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2 → v3：新增媒体扫描缓存表与上次扫描时间（增量扫描用）。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN lastScanSec INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_cache` (" +
                        "`key` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `isVideo` INTEGER NOT NULL, " +
                        "`dataPath` TEXT NOT NULL, `volumeName` TEXT NOT NULL, `bucketId` INTEGER NOT NULL, " +
                        "`bucketName` TEXT NOT NULL, `displayName` TEXT NOT NULL, `mimeType` TEXT NOT NULL, " +
                        "`size` INTEGER NOT NULL, `dateTakenMs` INTEGER NOT NULL, `dateAddedSec` INTEGER NOT NULL, " +
                        "`dateModifiedSec` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, `format` TEXT NOT NULL, `skipReason` TEXT, `motionPhotoOffset` INTEGER, " +
                        "`videoCodec` TEXT, `xmpCompressId` TEXT, PRIMARY KEY(`key`))",
                )
            }
        }

        /** v3 → v4：新增实况照片「视频段」独立档位（与普通视频档位解耦）。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN liveVideoTier TEXT NOT NULL DEFAULT 'BALANCED'")
            }
        }

        /**
         * v4 → v5：新增判类逻辑版本号。
         *
         * 判类规则变更后需要让旧的媒体缓存失效（否则未变化的文件会沿用旧判类结果）。
         * 默认 0 必然不等于当前 [CACHE_LOGIC_VERSION]，故升级后会自动触发一次全量重扫。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN cacheLogicVersion INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN compressPng INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "photo_compress.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { instance = it }
        }
    }
}
