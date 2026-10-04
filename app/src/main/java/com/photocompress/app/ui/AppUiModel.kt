package com.photocompress.app.ui

import com.photocompress.app.data.ledger.CompressedItemEntity
import com.photocompress.app.data.ledger.SettingsEntity
import com.photocompress.app.data.media.MediaItem
import com.photocompress.app.data.media.MediaKind
import com.photocompress.app.data.media.QualityTier

enum class AppPage { HOME, TODO, DONE, TRASH, SETTINGS }

/** 二级结构导航与选择状态（每个页面各一份）。 */
data class LevelState(
    val level: Int = 1,
    val album: String? = null,
    val pickedAlbums: Set<String> = emptySet(),
    val pickedItems: Set<Long> = emptySet(),
    val filter: String = "all",
)

data class UiState(
    val page: AppPage = AppPage.HOME,
    val scanning: Boolean = false,
    val scanError: String? = null,
    val lastScanAt: Long = 0L,
    val hasAllFilesAccess: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val ledger: List<CompressedItemEntity> = emptyList(),
    val settings: SettingsEntity = SettingsEntity(),
    val todo: LevelState = LevelState(),
    val done: LevelState = LevelState(),
    val busy: Boolean = false,
    val progress: Float = 0f,
    val progressLabel: String? = null,
)

/** 已压缩条目：账本记录 +（若仍在媒体库中的）媒体条目。 */
data class DoneMedia(
    val record: CompressedItemEntity,
    val item: MediaItem?,
) {
    val bucketName: String get() = item?.bucketName ?: record.bucketName
    val displayName: String get() = item?.displayName ?: record.displayName
    val dataPath: String get() = record.dataPath
    val kind: MediaKind get() = runCatching { MediaKind.valueOf(record.mediaKind) }.getOrDefault(MediaKind.PHOTO)
    val originalSize: Long get() = record.originalSize
    val compressedSize: Long get() = record.compressedSize
    val saved: Long get() = record.savedBytes
    val restorable: Boolean get() = record.restorable
    val dateTakenMs: Long get() = item?.dateTakenMs ?: record.originalDateTakenMs
    val qualityTier: QualityTier get() = QualityTier.fromName(record.qualityTier)
}

data class AlbumTodoUi(val name: String, val items: List<MediaItem>) {
    val count: Int get() = items.size
    val bytes: Long get() = items.sumOf { it.size }
    val compressibleItems: List<MediaItem> get() = items.filter { it.compressible }
    val compressibleCount: Int get() = compressibleItems.size
    val compressibleBytes: Long get() = compressibleItems.sumOf { it.size }
}

data class AlbumDoneUi(val name: String, val items: List<DoneMedia>) {
    val count: Int get() = items.size
    val before: Long get() = items.sumOf { it.originalSize }
    val after: Long get() = items.sumOf { it.compressedSize }
    val restorableItems: List<DoneMedia> get() = items.filter { it.restorable }
    val restorableCount: Int get() = restorableItems.size
}

data class Totals(
    val todoCount: Int,
    val todoBytes: Long,
    val doneCount: Int,
    val before: Long,
    val after: Long,
    val saved: Long,
    val pct: Float,
    val restorableCount: Int,
    val restorableBytes: Long,
)

private val ACTIVE_STATUSES = setOf(
    CompressedItemEntity.STATUS_DONE,
    CompressedItemEntity.STATUS_PURGED,
)

/** 批量操作前的统计与目标（口径唯一，按钮文案与确认框共用）。 */
data class SelectionSummary(
    val count: Int,
    val bytes: Long,
    val afterBytes: Long,
    val kindCounts: Map<MediaKind, Int>,
    val albumNames: Set<String> = emptySet(),
    val todoTargets: List<MediaItem> = emptyList(),
    val doneTargets: List<DoneMedia> = emptyList(),
) {
    val empty: Boolean get() = count == 0
    val savedBytes: Long get() = (bytes - afterBytes).coerceAtLeast(0)
}

private val EMPTY_SUMMARY = SelectionSummary(0, 0, 0, emptyMap())

/** 未压缩页当前选择（一级按图集，二级按图片）。 */
fun UiState.todoSelection(): SelectionSummary {
    val level = todo
    val targets: List<MediaItem>
    val albums: Set<String>
    if (level.level == 1) {
        val picked = albumTodoAll().filter { it.name in level.pickedAlbums && it.compressibleCount > 0 }
        targets = picked.flatMap { it.compressibleItems }
        albums = picked.map { it.name }.toSet()
    } else {
        val album = level.album ?: return EMPTY_SUMMARY
        albums = setOf(album)
        targets = albumTodoAll().firstOrNull { it.name == album }
            ?.items?.applyTodoFilter(level.filter)
            ?.filter { it.compressible && it.id in level.pickedItems }
            ?: emptyList()
    }
    return SelectionSummary(
        count = targets.size,
        bytes = targets.sumOf { it.size },
        afterBytes = 0,
        kindCounts = targets.groupingBy { it.kind }.eachCount(),
        albumNames = albums,
        todoTargets = targets,
    )
}

/** 已压缩页当前选择。 */
fun UiState.doneSelection(): SelectionSummary {
    val level = done
    val targets: List<DoneMedia>
    val albums: Set<String>
    if (level.level == 1) {
        val picked = albumDoneAll().filter { it.name in level.pickedAlbums && it.restorableCount > 0 }
        targets = picked.flatMap { it.restorableItems }
        albums = picked.map { it.name }.toSet()
    } else {
        val album = level.album ?: return EMPTY_SUMMARY
        albums = setOf(album)
        targets = albumDoneAll().firstOrNull { it.name == album }
            ?.items?.applyDoneFilter(level.filter)
            ?.filter { it.restorable && it.record.mediaStoreId in level.pickedItems }
            ?: emptyList()
    }
    return SelectionSummary(
        count = targets.size,
        bytes = targets.sumOf { it.originalSize },
        afterBytes = targets.sumOf { it.compressedSize },
        kindCounts = targets.groupingBy { it.kind }.eachCount(),
        albumNames = albums,
        doneTargets = targets,
    )
}

/** 未压缩：媒体库中有、且账本里没有有效压缩记录。 */
fun UiState.todoItems(): List<MediaItem> {
    val compressedPaths = ledger.asSequence()
        .filter { it.status in ACTIVE_STATUSES }
        .map { it.dataPath }
        .toHashSet()
    return items.filter { it.dataPath !in compressedPaths }
}

fun UiState.doneItems(): List<DoneMedia> {
    val byPath = items.associateBy { it.dataPath }
    return ledger.filter { it.status in ACTIVE_STATUSES }
        .map { DoneMedia(it, byPath[it.dataPath]) }
}

fun UiState.albumTodoAll(): List<AlbumTodoUi> =
    todoItems().groupBy { it.bucketName }
        .map { (name, list) -> AlbumTodoUi(name, list) }
        .sortedByDescending { it.count }

fun UiState.albumDoneAll(): List<AlbumDoneUi> =
    doneItems().groupBy { it.bucketName }
        .map { (name, list) -> AlbumDoneUi(name, list) }
        .sortedByDescending { it.count }

fun UiState.totals(): Totals {
    val todo = todoItems()
    val done = doneItems()
    val todoBytes = todo.sumOf { it.size }
    val before = done.sumOf { it.originalSize }
    val after = done.sumOf { it.compressedSize }
    val saved = before - after
    val restorable = done.filter { it.restorable }
    val pct = if (before + todoBytes > 0) saved.toFloat() / (before + todoBytes) * 100f else 0f
    return Totals(
        todoCount = todo.size,
        todoBytes = todoBytes,
        doneCount = done.size,
        before = before,
        after = after,
        saved = saved,
        pct = pct,
        restorableCount = restorable.size,
        restorableBytes = restorable.sumOf { it.originalSize },
    )
}

/** 二级：按筛选条件过滤当前图集的条目。 */
fun List<MediaItem>.applyTodoFilter(filter: String): List<MediaItem> = when (filter) {
    "skip" -> filter { !it.compressible }
    "photo" -> filter { it.compressible && it.kind == MediaKind.PHOTO }
    "live" -> filter { it.compressible && it.kind == MediaKind.LIVE_PHOTO }
    "video" -> filter { it.compressible && it.kind == MediaKind.VIDEO }
    else -> this
}

fun List<DoneMedia>.applyDoneFilter(filter: String): List<DoneMedia> = when (filter) {
    "restorable" -> filter { it.restorable }
    "expired" -> filter { !it.restorable }
    else -> this
}

/** 确认框正文的按类型列举（与按钮、统计共用同一口径）。 */
fun enumerateCounts(counts: Map<MediaKind, Int>): String {
    val parts = buildList {
        counts[MediaKind.PHOTO]?.takeIf { it > 0 }?.let { add("普通图片 $it 张") }
        counts[MediaKind.LIVE_PHOTO]?.takeIf { it > 0 }?.let { add("实况照片 $it 张") }
        counts[MediaKind.VIDEO]?.takeIf { it > 0 }?.let { add("视频 $it 条") }
    }
    return parts.joinToString("、").ifEmpty { "无" }
}
