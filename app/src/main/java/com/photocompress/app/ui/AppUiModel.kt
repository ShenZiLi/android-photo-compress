package com.photocompress.app.ui

import com.photocompress.app.data.ledger.CompressedItemEntity
import com.photocompress.app.data.ledger.SettingsEntity
import com.photocompress.app.data.media.MediaItem
import com.photocompress.app.data.media.MediaKind
import com.photocompress.app.data.media.QualityTier
import com.photocompress.app.data.media.ContainerFormat
import com.photocompress.app.data.media.SupportDecision
import androidx.core.net.toUri
import java.io.File
import com.photocompress.app.core.rewrite.RecoveryJournal

enum class AppPage { HOME, TODO, DONE, TRASH, SETTINGS, ALBUM_FILTER, COMPRESS_RATIO }

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
    /** true 表示正在做全量扫描（首次）而非增量同步。 */
    val fullScan: Boolean = false,
    val scanError: String? = null,
    val scanDone: Int = 0,
    val scanTotal: Int = 0,
    val lastScanAt: Long = 0L,
    val hasAllFilesAccess: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val ledger: List<CompressedItemEntity> = emptyList(),
    val recoveryEntries: List<RecoveryJournal.Entry> = emptyList(),
    val settings: SettingsEntity = SettingsEntity(),
    val todo: LevelState = LevelState(),
    val done: LevelState = LevelState(),
    val busy: Boolean = false,
    val progress: Float = 0f,
    val progressLabel: String? = null,
    /**
     * 全库派生结果。只随媒体库 / 账本 / 恢复日志 / 设置变化，与页面和选中项无关，
     * 因此 `copy()` 改页面或选择时会**原样带过去**，页面切换不再重算整库。
     * 由 [AppViewModel] 在后台线程重建。
     */
    val library: LibrarySnapshot = LibrarySnapshot.EMPTY,
) {
    /** 首次扫描进度（0~1）；总数未知时为 0。 */
    val scanProgress: Float
        get() = if (scanTotal > 0) scanDone.toFloat() / scanTotal else 0f

    /** 用户在设置页排除的图集，这些图集不出现在未压缩 / 已压缩页。 */
    val excludedAlbums: Set<String> get() = settings.excludedSet
}

/** 已压缩条目：账本记录 +（若仍在媒体库中的）媒体条目。 */
data class DoneMedia(
    val record: CompressedItemEntity,
    val item: MediaItem?,
    /**
     * true 表示由**文件内 XMP 标记**识别（账本缺失，如重装 / 清数据后）。
     * 这类条目可识别为「已压缩」以避免二次压缩，但压缩前大小与备份都不可知。
     */
    val adopted: Boolean = false,
) {
    val bucketName: String get() = item?.bucketName ?: record.bucketName
    val displayName: String get() = item?.displayName ?: record.displayName
    val dataPath: String get() = record.dataPath
    /**
     * 媒体类型在构造时解析一次。
     *
     * 原先是 `get()`，每次访问都要走一遍 `MediaKind.valueOf`（含异常开销），
     * 而汇总、筛选、排序都会读到它——一张已压缩照片会被解析好几次。
     */
    val kind: MediaKind = runCatching { MediaKind.valueOf(record.mediaKind) }.getOrDefault(MediaKind.PHOTO)
    val originalSize: Long get() = record.originalSize
    val compressedSize: Long get() = record.compressedSize
    val saved: Long get() = record.savedBytes
    val skipped: Boolean get() = record.skipped
    val restorable: Boolean get() = record.restorable
    val dateTakenMs: Long get() = item?.dateTakenMs ?: record.originalDateTakenMs
    val qualityTier: QualityTier get() = QualityTier.fromName(record.qualityTier)
}

/**
 * 未压缩页的一个图集。
 *
 * 聚合值（体积、可压缩项、待恢复项）在构造时算好。它们原本是 `get()`，
 * 而列表页的筛选、排序与每张卡片的文案都会反复读取——每次读取都要再遍历
 * 一遍该图集的全部条目，图集越多越明显。放在类体中只算一次，
 * 且不参与 `equals`（与 `get()` 语义一致）。
 */
data class AlbumTodoUi(val name: String, val items: List<MediaItem>) {
    val count: Int = items.size
    val bytes: Long = items.sumOf { it.size }
    val compressibleItems: List<MediaItem> = items.filter { it.compressible }
    val compressibleCount: Int = compressibleItems.size
    val compressibleBytes: Long = compressibleItems.sumOf { it.size }
    val pendingCount: Int = items.count { it.skipReason == RECOVERY_REASON }
}

private const val RECOVERY_REASON = "处理失败，原片恢复未完成，可在详情中重试恢复"

data class AlbumDoneUi(val name: String, val items: List<DoneMedia>) {
    val count: Int = items.size
    /** 实际压缩且压缩前大小已知的条目数；仅跳过时不显示相同大小的箭头对比。 */
    val knownBeforeCount: Int = items.count { !it.adopted && !it.skipped }
    val before: Long = items.sumOf { it.originalSize }
    val after: Long = items.sumOf { it.compressedSize }
    val saved: Long = items.filter { !it.adopted }.sumOf { it.saved.coerceAtLeast(0L) }
    val restorableItems: List<DoneMedia> = items.filter { it.restorable }
    val restorableCount: Int = restorableItems.size
    private val allSkipped: Boolean = items.all { it.skipped }
    val statusLine: String
        get() = if (restorableCount > 0) {
            "可还原 ${formatCount(restorableCount)} 项"
        } else {
            "备份已不可还原"
        }

    /** 全部条目都只有标记、压缩前大小未知时只显示当前体积，避免「0 B →」的误导性展示。 */
    val sizeLine: String
        get() = if (knownBeforeCount == 0) {
            "${formatCount(count)} 项 · ${formatSize(after)}"
        } else {
            "${formatCount(count)} 项 · ${formatSize(before)} → ${formatSize(after)}"
        }

    /** 图集卡片显示真实节省；只有文件标记而原体积未知时不虚构数字。 */
    val savingsLine: String
        get() {
            val reduction = if (knownBeforeCount > 0 || allSkipped) {
                "-${formatCompactSize(saved)}"
            } else {
                "—"
            }
            return "${formatCount(count)} 项 · $reduction"
        }
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
    /**
     * 三类媒体的**当前实际占用**（字节）：未压缩按原始大小，已压缩按压缩后大小。
     * 用于首页占用饼图——口径是"此刻占了多少"，不是"压缩前有多少"。
     * 缺失的类别视为 0（在 UI 侧补齐三行，保证图例条目稳定）。
     */
    val kindBytes: Map<MediaKind, Long>,
)

private val ACTIVE_STATUSES = setOf(
    CompressedItemEntity.STATUS_DONE,
    CompressedItemEntity.STATUS_PURGED,
    CompressedItemEntity.STATUS_SKIPPED,
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

/** 未压缩页可见的原片（媒体库中有、且账本与文件内标记都没有压缩痕迹）。 */
fun UiState.todoItems(): List<MediaItem> = library.todoItems

fun UiState.doneItems(): List<DoneMedia> = library.doneItems

/** 回收站仅承载成功压缩备份；失败事务在未压缩页处理。 */
fun UiState.successfulBackups(): List<CompressedItemEntity> = library.backups

/** 媒体库中出现过的全部图集（设置页用于配置过滤，不排除任何项）。 */
fun UiState.allAlbumNames(): List<Pair<String, Int>> = library.albumNames

/** 仅凭文件内标记识别出的条目：压缩前大小未知，不可还原。 */
private fun adoptedRecord(item: MediaItem): CompressedItemEntity = CompressedItemEntity(
    id = item.xmpCompressId ?: "unknown",
    mediaStoreId = item.id,
    dataPath = item.dataPath,
    volumeName = item.volumeName,
    bucketName = item.bucketName,
    displayName = item.displayName,
    mediaKind = item.kind.name,
    mimeType = item.mimeType,
    containerFormat = item.format.name,
    videoCodec = item.videoCodec,
    originalSize = 0L,
    compressedSize = item.size,
    originalSha256 = "",
    originalDateTakenMs = item.dateTakenMs,
    originalDateAddedSec = item.dateAddedSec,
    originalDateModifiedSec = item.dateModifiedSec,
    qualityTier = QualityTier.BALANCED.name,
    codecUsed = null,
    compressedAtMs = 0L,
    restoreDeadlineMs = 0L,
    backupRelPath = null,
    backupSize = 0L,
    status = CompressedItemEntity.STATUS_DONE,
)

fun UiState.albumTodoAll(): List<AlbumTodoUi> = library.albumTodoAll

fun UiState.albumDoneAll(): List<AlbumDoneUi> = library.albumDoneAll

fun UiState.totals(): Totals = library.totals

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
    "expired" -> filter { !it.restorable && !it.skipped }
    "skipped" -> filter { it.skipped }
    else -> this
}

/** 确认框正文的按类型列举（与按钮、统计共用同一口径）。 */
fun enumerateCounts(counts: Map<MediaKind, Int>): String {
    val parts = buildList {
        counts[MediaKind.PHOTO]?.takeIf { it > 0 }?.let { add("图片 $it 张") }
        counts[MediaKind.LIVE_PHOTO]?.takeIf { it > 0 }?.let { add("实况 $it 张") }
        counts[MediaKind.VIDEO]?.takeIf { it > 0 }?.let { add("视频 $it 条") }
    }
    return parts.joinToString("、").ifEmpty { "无" }
}

/**
 * 对比条的两段占比。
 *
 * 必须用**原始数值**（字节 / 个数）计算：早期实现从格式化字符串里抠数字，
 * 单位不一致时会得出「301 KB > 67 GB」这类错误比例。
 */
fun comparisonRatios(left: Double, right: Double): Pair<Float, Float> {
    val sum = left + right
    if (sum <= 0.0 || !left.isFinite() || !right.isFinite()) return 0.5f to 0.5f
    val leftRatio = (left / sum).toFloat().coerceIn(0.0001f, 0.9999f)
    return leftRatio to (1f - leftRatio)
}

// ---------------------------------------------------------------- 全库派生结果

/**
 * 全库派生结果：未压缩项 / 已压缩项 / 图集分组 / 汇总 / 回收站备份 / 图集名单。
 *
 * 这些结果**只依赖媒体库、账本、恢复日志与设置**，与当前页面、层级和选中项无关。
 * 因此在构造时一次性算好，并**由 ViewModel 在后台线程重建**：
 *
 * - 切换页面只是 `UiState.copy(page = …)`，[UiState.library] 原样带过去，
 *   首帧不再承担整库重算——这正是过渡动画掉帧的根因；
 * - 同一个 [UiState] 下的多个调用点（顶栏统计、列表页、图集页、首页汇总）
 *   共享同一份结果，不再各自重算一遍。
 */
class LibrarySnapshot(
    private val items: List<MediaItem> = emptyList(),
    private val ledger: List<CompressedItemEntity> = emptyList(),
    private val recoveryEntries: List<RecoveryJournal.Entry> = emptyList(),
    private val settings: SettingsEntity = SettingsEntity(),
) {
    /** 输入是否与 [state] 一致。列表按引用比较，这里是 O(1)。 */
    fun matches(state: UiState): Boolean =
        items === state.items && ledger === state.ledger &&
            recoveryEntries === state.recoveryEntries && settings == state.settings

    /** 在原子更新内发布结果，保留当前导航与选择；计算期间输入变更则丢弃。 */
    internal fun applyTo(state: UiState): UiState =
        if (matches(state)) state.copy(library = this) else state

    private val excludedAlbums: Set<String> = settings.excludedSet

    /** 无收益判断只适用于仍然存在且身份/大小/日期一致的原片，不隐藏改过或替换过的图片。 */
    private fun activeLedger(): List<CompressedItemEntity> {
        val byPath = items.associateBy { it.dataPath }
        val pending = recoveryEntries.flatMap { it.paths }.toSet()
        return ledger.filter { record ->
            record.dataPath !in pending && record.originalPath !in pending &&
            record.status in ACTIVE_STATUSES && (!record.skipped || byPath[record.dataPath]?.let { item ->
                item.id == record.mediaStoreId && item.volumeName == record.volumeName &&
                    item.size == record.originalSize && item.dateModifiedSec == record.originalDateModifiedSec &&
                    item.xmpCompressId == null
            } == true)
        }
    }

    private fun buildTodoItems(): List<MediaItem> {
        val pending = buildMap {
            recoveryEntries.forEach { entry -> entry.paths.forEach { put(it, entry) } }
            items.forEach { item -> recoveryEntries.firstOrNull { it.matches(item.dataPath, item.uri.toString()) }?.let { put(item.dataPath, it) } }
        }
        val compressedPaths = HashSet<String>()
        activeLedger().forEach { compressedPaths += it.dataPath }
        // 文件内标记优先：账本丢失（重装 / 清数据）时仍能识别，避免二次压缩（F7 / AC5）
        items.asSequence().filter { it.xmpCompressId != null && it.dataPath !in pending }.forEach { compressedPaths += it.dataPath }
        val available = items.filter { it.dataPath !in compressedPaths && it.bucketName !in excludedAlbums }
            .map {
                when {
                    it.dataPath in pending -> it.copy(support = SupportDecision.Skipped(RECOVERY_REASON))
                    // PNG 开关决定列表候选，不能复用扫描缓存的转换预检结果。
                    // 安全转换检查仍由引擎在写入原片前完成；待恢复事务优先禁止重试压缩。
                    it.format == ContainerFormat.PNG -> it.copy(support = if (settings.compressPng) {
                        SupportDecision.Supported
                    } else {
                        SupportDecision.Skipped("PNG 压缩未开启")
                    })
                    else -> it
                }
            }
        val paths = items.map { it.dataPath }.toSet()
        val missing = recoveryEntries.filter { entry -> entry.paths.none { it in paths } && items.none { entry.matches(it.dataPath, it.uri.toString()) } }.map { entry ->
            val uri = entry.mediaUri.toUri()
            val video = uri.path?.contains("/video/") == true
            val file = File(entry.path)
            MediaItem(
                id = uri.lastPathSegment?.toLongOrNull() ?: (-entry.id.hashCode().toLong()).coerceAtMost(-1L),
                uri = uri, dataPath = entry.path, volumeName = "external_primary", bucketId = 0L,
                bucketName = file.parentFile?.name ?: "待恢复", displayName = file.name,
                mimeType = if (video) "video/mp4" else "image/jpeg", size = file.length(),
                dateTakenMs = entry.dateTakenMs, dateAddedSec = entry.dateAddedSec, dateModifiedSec = entry.dateModifiedSec,
                width = 0, height = 0, kind = if (video) MediaKind.VIDEO else MediaKind.PHOTO,
                format = if (video) ContainerFormat.MP4 else ContainerFormat.UNKNOWN,
                support = SupportDecision.Skipped(RECOVERY_REASON),
            )
        }.filter { it.bucketName !in excludedAlbums }
        return available + missing
    }

    private fun buildDoneItems(): List<DoneMedia> {
        val byPath = items.associateBy { it.dataPath }
        val fromLedger = activeLedger()
            .map { DoneMedia(it, byPath[it.dataPath]) }
            .filter { it.bucketName !in excludedAlbums }
        val known = fromLedger.map { it.dataPath }.toHashSet()
        val adopted = items
            .filter { it.xmpCompressId != null && it.dataPath !in known && it.bucketName !in excludedAlbums &&
                recoveryEntries.none { entry -> entry.matches(it.dataPath, it.uri.toString()) } }
            .map { DoneMedia(adoptedRecord(it), it, adopted = true) }
        return fromLedger + adopted
    }

    val todoItems: List<MediaItem> = buildTodoItems()

    val doneItems: List<DoneMedia> = buildDoneItems()

    val albumTodoAll: List<AlbumTodoUi> = todoItems.groupBy { it.bucketName }
        .map { (name, list) -> AlbumTodoUi(name, list) }
        .sortedByDescending { it.count }

    val albumDoneAll: List<AlbumDoneUi> = doneItems.groupBy { it.bucketName }
        .map { (name, list) -> AlbumDoneUi(name, list) }
        .sortedByDescending { it.count }

    /** 回收站仅承载成功压缩备份；失败事务在未压缩页处理。 */
    val backups: List<CompressedItemEntity> = run {
        val pending = recoveryEntries.flatMap { it.paths }.toSet()
        ledger.filter { it.backupRelPath != null &&
            it.status in setOf(CompressedItemEntity.STATUS_DONE, CompressedItemEntity.STATUS_PURGED) &&
            it.dataPath !in pending && it.originalPath !in pending }
    }

    /** 媒体库中出现过的全部图集（设置页用于配置过滤，不排除任何项）。 */
    val albumNames: List<Pair<String, Int>> = items.groupBy { it.bucketName }
        .map { (name, list) -> name to list.size }
        .sortedByDescending { it.second }

    val totals: Totals = run {
        val todo = todoItems
        val done = doneItems
        // 由文件标记识别（账本已丢失）的条目没有压缩前大小，不计入节省统计，避免出现负值
        val known = done.filter { !it.adopted }
        val todoBytes = todo.sumOf { it.size }
        val before = known.sumOf { it.originalSize }
        val after = known.sumOf { it.compressedSize }
        val saved = (before - after).coerceAtLeast(0)
        val restorable = done.filter { it.restorable }
        val pct = if (before + todoBytes > 0) saved.toFloat() / (before + todoBytes) * 100f else 0f

        // 三类媒体的当前占用：未压缩取原始大小；已压缩优先取实时文件大小
        // （adopted 条目账本缺失、compressedSize 不可信，只要文件还在库里就以实际为准）。
        val kindBytes = HashMap<MediaKind, Long>()
        for (m in todo) kindBytes[m.kind] = (kindBytes[m.kind] ?: 0L) + m.size
        for (d in done) {
            val current = d.item?.size ?: d.compressedSize
            kindBytes[d.kind] = (kindBytes[d.kind] ?: 0L) + current
        }

        Totals(
            todoCount = todo.size,
            todoBytes = todoBytes,
            doneCount = done.size,
            before = before,
            after = after,
            saved = saved,
            pct = pct,
            restorableCount = restorable.size,
            restorableBytes = restorable.sumOf { it.originalSize },
            kindBytes = kindBytes,
        )
    }

    companion object {
        /** 空库快照；与「尚未扫描」的初始状态等价。 */
        val EMPTY = LibrarySnapshot()
    }
}
