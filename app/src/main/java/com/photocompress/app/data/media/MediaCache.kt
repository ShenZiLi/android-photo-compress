package com.photocompress.app.data.media

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * 媒体库扫描结果的持久化缓存。
 *
 * 首次（或缓存失效时）做一次全量扫描并整体写入；之后每次启动只对
 * 「新增 / 元数据变化 / 已删除」的条目做增量更新，未变化项直接复用缓存，
 * 避免每次都读一遍每个文件的头部与轨道信息。
 */
@Entity(tableName = "media_cache")
data class CachedMediaEntity(
    /** `image:<id>` / `video:<id>`。两个集合的 _ID 各自独立，需加前缀区分。 */
    @PrimaryKey val key: String,
    val mediaStoreId: Long,
    val isVideo: Boolean,
    val dataPath: String,
    val volumeName: String,
    val bucketId: Long,
    val bucketName: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val dateTakenMs: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val width: Int,
    val height: Int,
    val kind: String,
    val format: String,
    /** 非空表示不可压缩及原因；空表示可压缩。 */
    val skipReason: String?,
    val motionPhotoOffset: Long?,
    val videoCodec: String?,
    val xmpCompressId: String?,
)

@Dao
interface MediaCacheDao {
    @Query("SELECT * FROM media_cache")
    suspend fun all(): List<CachedMediaEntity>

    @Upsert
    suspend fun upsertAll(items: List<CachedMediaEntity>)

    /** 用全量扫描结果整体替换缓存（清空 + 写入在同一事务内，避免出现半截缓存）。 */
    @Transaction
    suspend fun replaceAll(items: List<CachedMediaEntity>) {
        clear()
        upsertAll(items)
    }

    @Query("DELETE FROM media_cache WHERE key IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)

    @Query("DELETE FROM media_cache WHERE dataPath IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM media_cache")
    suspend fun clear()
}

fun mediaCacheKey(isVideo: Boolean, mediaStoreId: Long): String =
    if (isVideo) "video:$mediaStoreId" else "image:$mediaStoreId"

/** 判定缓存条目是否需要重建时使用的轻量指纹。 */
data class CacheSnapshot(
    val dataPath: String,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
)

/**
 * **判类逻辑版本号**——缓存的有效性还取决于它，而不是只看文件指纹。
 *
 * 文件没变不代表判类结果没变：判类规则（可处理格式、编码能力门槛、HDR 策略等）
 * 一旦调整，旧的 `skipReason` 会被 `diffCache` 当作「未变化」一直复用，
 * 导致新逻辑永远不生效（实测踩过：D11 修订后，52 条 HDR 视频仍显示旧文案）。
 *
 * **凡改动判类 / 探测逻辑（`MediaClassifier`、`VideoProbeRunner`、编码能力判定），
 * 必须把此值 +1**，强制下一次启动走全量重扫。
 */
const val CACHE_LOGIC_VERSION = 4

/**
 * 是否需要走全量重扫：扫描水位还没推进，**或**判类逻辑版本已变化。
 *
 * 纯函数，便于单元测试。缓存「可用」（warm）的判定即 `!needsFullScan(...)`。
 */
fun needsFullScan(
    lastScanSec: Long,
    cachedLogicVersion: Int,
    currentLogicVersion: Int = CACHE_LOGIC_VERSION,
): Boolean = lastScanSec <= 0L || cachedLogicVersion != currentLogicVersion

/** 增量扫描的差异结果。 */
data class CacheDiff(
    /** 新增或指纹变化、需要重新做文件级探测的条目。 */
    val changedKeys: Set<String>,
    /** 媒体库中已不存在、需要从缓存删除的条目。 */
    val removedKeys: Set<String>,
)

/**
 * 计算增量扫描的差异：只重新探测「新增或已变化」的条目，只删除「已消失」的条目。
 * 纯函数，便于单元测试。
 */
fun diffCache(cached: Map<String, CacheSnapshot>, live: Map<String, CacheSnapshot>): CacheDiff {
    val changed = HashSet<String>()
    for ((key, snapshot) in live) {
        if (cached[key] != snapshot) changed += key
    }
    val removed = cached.keys.filterTo(HashSet()) { it !in live }
    return CacheDiff(changed, removed)
}
