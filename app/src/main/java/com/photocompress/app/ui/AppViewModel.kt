package com.photocompress.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photocompress.app.core.compress.CompressOutcome
import com.photocompress.app.core.compress.CompressionEngine
import com.photocompress.app.core.compress.RestoreOutcome
import com.photocompress.app.data.ledger.AppDatabase
import com.photocompress.app.data.ledger.CompressedItemEntity
import com.photocompress.app.data.ledger.SettingsEntity
import com.photocompress.app.data.media.MediaItem
import com.photocompress.app.data.media.MediaKind
import com.photocompress.app.data.media.MediaRepository
import com.photocompress.app.data.media.QualityTier
import com.photocompress.app.data.media.StorageAccess
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BatchState(val label: String, val progress: Float, val done: Int, val total: Int)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val ledgerDao = db.ledgerDao()
    private val settingsDao = db.settingsDao()
    private val mediaCacheDao = db.mediaCacheDao()
    private val mediaRepo = MediaRepository(app, mediaCacheDao)
    private val engine = CompressionEngine(app)

    private val _ui = MutableStateFlow(UiState(hasAllFilesAccess = StorageAccess.hasAllFilesAccess(app)))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _batch = MutableStateFlow<BatchState?>(null)
    val batch: StateFlow<BatchState?> = _batch.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var writing = false

    init {
        viewModelScope.launch {
            settingsDao.observe().collect { s ->
                _ui.update { it.copy(settings = s ?: SettingsEntity()) }
            }
        }
        refresh()
    }

    // ---------------------------------------------------------------- 扫描

    fun refresh() {
        if (writing) return
        viewModelScope.launch {
            val settings = settingsDao.get() ?: SettingsEntity()
            // lastScanSec > 0 表示缓存已就绪：只需增量扫描，不再全量枚举
            val warm = settings.lastScanSec > 0L
            _ui.update {
                it.copy(
                    scanning = true,
                    fullScan = !warm,
                    scanError = null,
                    scanDone = 0,
                    scanTotal = 0,
                    lastScanAt = if (it.lastScanAt > 0) it.lastScanAt else settings.lastScanSec * 1000L,
                )
            }
            try {
                val onProgress: (Int, Int) -> Unit = { done, total ->
                    _ui.update { it.copy(scanDone = done, scanTotal = total) }
                }
                val items = if (warm) {
                    mediaRepo.incrementalScan(onProgress)
                } else {
                    mediaRepo.fullScan(onProgress)
                }
                val ledger = ledgerDao.observeAll().first()
                // 缓存写入成功后再推进扫描水位；中途退出时水位不变，下次仍会重扫
                val latest = settingsDao.get() ?: settings
                settingsDao.upsert(latest.copy(lastScanSec = System.currentTimeMillis() / 1000))
                android.util.Log.i(TAG, "scan ${if (warm) "incremental" else "full"}: ${items.size} items")
                _ui.update {
                    it.copy(
                        scanning = false,
                        fullScan = false,
                        items = items,
                        ledger = ledger,
                        lastScanAt = System.currentTimeMillis(),
                        hasAllFilesAccess = StorageAccess.hasAllFilesAccess(getApplication()),
                    )
                }
            } catch (t: Throwable) {
                _ui.update { it.copy(scanning = false, fullScan = false, scanError = t.message ?: "扫描失败") }
            }
        }
    }

    /**
     * 本应用原地改写过的文件，其 MediaStore 时间戳可能被还原（mtime 保持不变），
     * 增量对比无法感知；这里显式失效这些路径的缓存，让下次扫描重新探测。
     */
    private suspend fun invalidateCache(paths: Collection<String>) {
        val list = paths.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) return
        list.chunked(200).forEach { mediaCacheDao.deleteByPaths(it) }
    }

    // ---------------------------------------------------------------- 导航与选择

    fun go(page: AppPage) = _ui.update { it.copy(page = page) }

    fun openAlbum(page: AppPage, name: String) = updateLevel(page) {
        it.copy(level = 2, album = name, pickedItems = emptySet(), filter = "all")
    }

    fun backToAlbums(page: AppPage) = updateLevel(page) {
        it.copy(level = 1, album = null, pickedItems = emptySet(), filter = "all")
    }

    fun setFilter(page: AppPage, filter: String) = updateLevel(page) {
        it.copy(filter = filter, pickedItems = emptySet())
    }

    fun toggleAlbum(page: AppPage, name: String) = updateLevel(page) {
        val picked = if (name in it.pickedAlbums) it.pickedAlbums - name else it.pickedAlbums + name
        it.copy(pickedAlbums = picked)
    }

    fun selectAllAlbums(page: AppPage) {
        val state = _ui.value
        val names = when (page) {
            AppPage.TODO -> state.albumTodoAll().filter { it.compressibleCount > 0 }.map { it.name }
            AppPage.DONE -> state.albumDoneAll().filter { it.restorableCount > 0 }.map { it.name }
            else -> emptyList()
        }
        updateLevel(page) {
            val all = names.isNotEmpty() && names.all { n -> n in it.pickedAlbums }
            it.copy(pickedAlbums = if (all) it.pickedAlbums - names.toSet() else it.pickedAlbums + names)
        }
    }

    fun toggleItem(page: AppPage, id: Long) = updateLevel(page) {
        val picked = if (id in it.pickedItems) it.pickedItems - id else it.pickedItems + id
        it.copy(pickedItems = picked)
    }

    fun selectAllItems(page: AppPage) {
        val state = _ui.value
        val level = if (page == AppPage.TODO) state.todo else state.done
        val album = level.album ?: return
        val act: List<Long> = when (page) {
            AppPage.TODO -> state.albumTodoAll().firstOrNull { it.name == album }
                ?.items?.applyTodoFilter(level.filter)?.filter { it.compressible }?.map { it.id } ?: emptyList()
            AppPage.DONE -> state.albumDoneAll().firstOrNull { it.name == album }
                ?.items?.applyDoneFilter(level.filter)?.filter { it.restorable }?.map { it.record.mediaStoreId } ?: emptyList()
            else -> emptyList()
        }
        updateLevel(page) {
            val all = act.isNotEmpty() && act.all { id -> id in it.pickedItems }
            it.copy(pickedItems = if (all) it.pickedItems - act.toSet() else it.pickedItems + act)
        }
    }

    private fun updateLevel(page: AppPage, transform: (LevelState) -> LevelState) = _ui.update { s ->
        when (page) {
            AppPage.TODO -> s.copy(todo = transform(s.todo))
            AppPage.DONE -> s.copy(done = transform(s.done))
            else -> s
        }
    }

    // ---------------------------------------------------------------- 批量压缩 / 还原

    fun compress(summary: SelectionSummary) {
        if (summary.empty || _batch.value != null) return
        val targets = summary.todoTargets
        viewModelScope.launch {
            writing = true
            val touched = LinkedHashSet<String>()
            try {
                val tiers = _ui.value.settings
                var done = 0
                var savedBytes = 0L
                var skipped = 0
                var failed = 0
                val failures = mutableListOf<String>()
                _batch.value = BatchState("正在压缩", 0f, 0, targets.size)
                for ((index, item) in targets.withIndex()) {
                    val tier = tierFor(item.kind, tiers)
                    // 实况照片内嵌视频取独立的「实况视频段」档位；只有普通视频才用「视频」档位
                    val videoTier = if (item.kind == MediaKind.VIDEO) {
                        QualityTier.fromName(tiers.videoTier)
                    } else {
                        QualityTier.fromName(tiers.liveVideoTier)
                    }
                    when (val outcome = engine.compress(item, tier, videoTier)) {
                        is CompressOutcome.Success -> {
                            ledgerDao.upsert(outcome.record)
                            done++
                            savedBytes += outcome.record.savedBytes
                            touched += outcome.record.dataPath
                            if (outcome.record.originalPath.isNotBlank()) touched += outcome.record.originalPath
                            android.util.Log.i(TAG, "${item.displayName}: OK ${outcome.record.originalSize} -> ${outcome.record.compressedSize} (${outcome.record.codecUsed})")
                        }
                        is CompressOutcome.Skipped -> {
                            skipped++
                            failures += "${item.displayName}：${outcome.reason}"
                            android.util.Log.i(TAG, "${item.displayName}: SKIP ${outcome.reason}")
                        }
                        is CompressOutcome.Failed -> {
                            failed++
                            failures += "${item.displayName}：${outcome.reason}"
                            android.util.Log.w(TAG, "${item.displayName}: FAIL ${outcome.reason}")
                        }
                    }
                    _batch.value = BatchState("正在压缩", (index + 1f) / targets.size, index + 1, targets.size)
                }
                clearSelection(AppPage.TODO)
                reloadLedger()
                _messages.trySend(
                    buildString {
                        append("压缩成功 · $done 项")
                        if (savedBytes > 0) append(" · 节省 ${formatSize(savedBytes)}")
                        if (skipped > 0) append(" · 跳过 $skipped 项")
                        if (failed > 0) append(" · 失败 $failed 项")
                    }
                )
                if (failures.isNotEmpty()) {
                    _messages.trySend(failures.take(3).joinToString("\n"))
                }
            } finally {
                writing = false
                _batch.value = null
                invalidateCache(touched)
                refresh()
            }
        }
    }

    fun restore(summary: SelectionSummary) {
        if (summary.empty || _batch.value != null) return
        val targets = summary.doneTargets
        viewModelScope.launch {
            writing = true
            val touched = LinkedHashSet<String>()
            try {
                var done = 0
                var freedBytes = 0L
                var failed = 0
                _batch.value = BatchState("正在还原", 0f, 0, targets.size)
                for ((index, dm) in targets.withIndex()) {
                    when (val outcome = engine.restore(dm.record)) {
                        is RestoreOutcome.Success -> {
                            ledgerDao.deleteById(dm.record.id)
                            ledgerDao.deleteByPath(dm.record.dataPath)
                            if (dm.record.originalPath.isNotBlank()) {
                                ledgerDao.deleteByPath(dm.record.originalPath)
                            }
                            touched += dm.record.dataPath
                            if (dm.record.originalPath.isNotBlank()) touched += dm.record.originalPath
                            done++
                            freedBytes += dm.compressedSize
                        }
                        is RestoreOutcome.Failed -> {
                            failed++
                            _messages.trySend("${dm.displayName}：${outcome.reason}")
                        }
                    }
                    _batch.value = BatchState("正在还原", (index + 1f) / targets.size, index + 1, targets.size)
                }
                clearSelection(AppPage.DONE)
                reloadLedger()
                _messages.trySend(
                    buildString {
                        append("还原成功 · $done 项")
                        if (freedBytes > 0) append(" · 占用 +${formatSize(freedBytes)}")
                        if (failed > 0) append(" · 失败 $failed 项")
                    }
                )
            } finally {
                writing = false
                _batch.value = null
                invalidateCache(touched)
                refresh()
            }
        }
    }

    fun clearSelection(page: AppPage) = updateLevel(page) {
        it.copy(pickedAlbums = emptySet(), pickedItems = emptySet())
    }

    // ---------------------------------------------------------------- 回收站

    fun purgeAllBackups() {
        val records = _ui.value.ledger.filter {
            it.status == CompressedItemEntity.STATUS_DONE && it.backupRelPath != null
        }
        if (records.isEmpty()) return
        viewModelScope.launch {
            writing = true
            try {
                val freed = engine.purgeBackups(records)
                ledgerDao.purgeAllBackups()
                reloadLedger()
                _messages.trySend("已清理备份，释放 ${formatSize(freed)}")
            } finally {
                writing = false
                refresh()
            }
        }
    }

    /** 到期自动清理（F11）：删除超期备份，已压缩照片不受影响。 */
    fun purgeExpired() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val expired = ledgerDao.findExpired(now)
            if (expired.isEmpty()) return@launch
            engine.purgeBackups(expired)
            expired.forEach { ledgerDao.markBackupGone(it.dataPath, CompressedItemEntity.STATUS_PURGED) }
            reloadLedger()
        }
    }

    // ---------------------------------------------------------------- 设置

    fun setTier(kind: MediaKind, tier: QualityTier) {
        viewModelScope.launch {
            val current = settingsDao.get() ?: SettingsEntity()
            val next = when (kind) {
                MediaKind.PHOTO -> current.copy(photoTier = tier.name)
                MediaKind.LIVE_PHOTO -> current.copy(liveTier = tier.name)
                MediaKind.VIDEO -> current.copy(videoTier = tier.name)
            }
            settingsDao.upsert(next)
        }
    }

    /** 实况照片「视频段」档位：仅作用于实况照片内嵌视频，不影响普通视频。 */
    fun setLiveVideoTier(tier: QualityTier) {
        viewModelScope.launch {
            val current = settingsDao.get() ?: SettingsEntity()
            settingsDao.upsert(current.copy(liveVideoTier = tier.name))
        }
    }

    private fun tierFor(kind: MediaKind, s: SettingsEntity): QualityTier = when (kind) {
        MediaKind.PHOTO -> QualityTier.fromName(s.photoTier)
        MediaKind.LIVE_PHOTO -> QualityTier.fromName(s.liveTier)
        MediaKind.VIDEO -> QualityTier.fromName(s.videoTier)
    }

    /** 图集过滤（F9/F10 的补充）：被排除的图集不出现在未压缩 / 已压缩页。 */
    fun setAlbumExcluded(name: String, excluded: Boolean) {
        viewModelScope.launch {
            val current = settingsDao.get() ?: SettingsEntity()
            val set = current.excludedSet.toMutableSet()
            if (excluded) set += name else set -= name
            settingsDao.upsert(current.copy(excludedAlbums = set.sorted().joinToString("\n")))
            _messages.trySend(if (excluded) "已排除图集「$name」" else "已恢复显示图集「$name」")
        }
    }

    // ---------------------------------------------------------------- 内部

    private suspend fun reloadLedger() {
        _ui.update { it.copy(ledger = ledgerDao.observeAll().first()) }
    }

    fun refreshPermission() = _ui.update {
        it.copy(hasAllFilesAccess = StorageAccess.hasAllFilesAccess(getApplication()))
    }

    val recycleBinSize: Long get() = engine.recycleBinSize()

    companion object {
        private const val TAG = "PCCompress"
    }
}
