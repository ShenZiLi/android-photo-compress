package com.photocompress.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photocompress.app.core.compress.CompressOutcome
import com.photocompress.app.core.compress.CompressionEngine
import com.photocompress.app.core.compress.CompressionControl
import com.photocompress.app.core.compress.RestoreOutcome
import com.photocompress.app.data.ledger.AppDatabase
import com.photocompress.app.data.ledger.CompressedItemEntity
import com.photocompress.app.data.ledger.SettingsEntity
import com.photocompress.app.data.media.CACHE_LOGIC_VERSION
import com.photocompress.app.data.media.MediaItem
import com.photocompress.app.data.media.MediaKind
import com.photocompress.app.data.media.MediaRepository
import com.photocompress.app.data.media.QualityTier
import com.photocompress.app.data.media.StorageAccess
import com.photocompress.app.data.media.needsFullScan
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.photocompress.app.core.rewrite.RecoveryJournal

data class BatchState(
    val label: String,
    val progress: Float,
    val done: Int,
    val total: Int,
    val canCancel: Boolean = false,
    val cancelling: Boolean = false,
    val currentName: String? = null,
)

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
    private var compressionControl: CompressionControl? = null

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
        if (writing || _ui.value.scanning) return
        viewModelScope.launch {
            val settings = settingsDao.get() ?: SettingsEntity()
            // 缓存可用需同时满足两条：扫描水位已推进 + 判类逻辑版本未变。
            // 只看 lastScanSec 会漏掉「判类规则已改但文件没变」的情况——
            // 那样旧 skipReason 会被一直复用（D11 修订后 52 条 HDR 视频即由此被卡住）。
            val warm = !needsFullScan(settings.lastScanSec, settings.cacheLogicVersion)
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
                val affected = withContext(Dispatchers.IO) {
                    val records = ledgerDao.observeAll().first()
                    val paths = engine.reconcileRolledBack(records, ledgerDao::markFailed, ledgerDao::deleteById).toMutableSet()
                    for (entry in engine.pendingRecovery()) {
                        paths += entry.paths
                        engine.recover(entry, ledgerDao::deleteById)
                    }
                    paths
                }
                invalidateCache(affected)
                val items = if (warm) {
                    mediaRepo.incrementalScan(onProgress)
                } else {
                    mediaRepo.fullScan(onProgress)
                }
                val ledger = ledgerDao.observeAll().first()
                val recoveryState = withContext(Dispatchers.IO) {
                    engine.pendingRecovery()
                }
                // 缓存写入成功后再推进扫描水位；中途退出时水位不变，下次仍会重扫
                settingsDao.insertIfMissing(settings)
                // 只更新扫描水位，不能用旧设置快照覆盖用户刚保存的 PNG 开关或质量档。
                settingsDao.markScanCompleted(System.currentTimeMillis() / 1000, CACHE_LOGIC_VERSION)
                android.util.Log.i(TAG, "scan ${if (warm) "incremental" else "full"}: ${items.size} items")
                _ui.update {
                    it.copy(
                        scanning = false,
                        fullScan = false,
                        items = items,
                        ledger = ledger,
                        recoveryEntries = recoveryState,
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
        if (summary.empty || writing || _batch.value != null || _ui.value.scanning) return
        val targets = summary.todoTargets
        val control = CompressionControl()
        compressionControl = control
        writing = true
        _batch.value = BatchState("正在压缩", 0f, 0, targets.size, canCancel = true)
        viewModelScope.launch {
            val touched = LinkedHashSet<String>()
            try {
                val tiers = _ui.value.settings
                var done = 0
                var savedBytes = 0L
                var skipped = 0
                var failed = 0
                // 同一批次按首次出现顺序收集不同原因，统计仍按实际项目计数。
                val failures = linkedSetOf<String>()
                for ((index, item) in targets.withIndex()) {
                    if (control.isCancellationRequested) break
                    _batch.update { it?.copy(currentName = item.displayName) }
                    val tier = tierFor(item.kind, tiers)
                    // 实况照片内嵌视频取独立的「实况视频段」档位；只有普通视频才用「视频」档位
                    val videoTier = if (item.kind == MediaKind.VIDEO) {
                        QualityTier.fromName(tiers.videoTier)
                    } else {
                        QualityTier.fromName(tiers.liveVideoTier)
                    }
                    when (val outcome = engine.compress(
                        item, tier, videoTier, control,
                        onCommit = ledgerDao::upsert,
                        onRollback = ledgerDao::deleteById,
                        pngEnabled = tiers.compressPng,
                    )) {
                        is CompressOutcome.Success -> {
                            done++
                            savedBytes += outcome.record.savedBytes
                            touched += outcome.record.dataPath
                            if (outcome.record.originalPath.isNotBlank()) touched += outcome.record.originalPath
                            android.util.Log.i(TAG, "${item.displayName}: OK ${outcome.record.originalSize} -> ${outcome.record.compressedSize} (${outcome.record.codecUsed})")
                        }
                        is CompressOutcome.Skipped -> {
                            skipped++
                            failures += outcome.reason
                            android.util.Log.i(TAG, "${item.displayName}: SKIP ${outcome.reason}")
                        }
                        is CompressOutcome.Failed -> {
                            touched += item.dataPath
                            failed++
                            failures += "${item.displayName}：${outcome.reason}"
                            android.util.Log.w(TAG, "${item.displayName}: FAIL ${outcome.reason}")
                            break // 错误可能来自存储或媒体库；保留已完成项，及时停止后续改写。
                        }
                        is CompressOutcome.Cancelled -> {
                            touched += item.dataPath
                            outcome.reason?.let { failed++; failures += it }
                            break
                        }
                    }
                    _batch.update { it?.copy(progress = (index + 1f) / targets.size, done = index + 1, currentName = null) }
                }
                // 此时当前项已经完成或安全回退，取消信号不再作用于历史项目。
                compressionControl = null
                _batch.update { it?.copy(label = "正在更新", canCancel = false, currentName = null) }
                clearSelection(AppPage.TODO)
                _messages.trySend(
                    buildString {
                        if (control.isCancellationRequested) append("已取消，")
                        append("压缩${done}项")
                        if (savedBytes > 0) append("，节省${formatSize(savedBytes).replace(" ", "")}")
                        if (skipped > 0) append("，跳过${skipped}项")
                        if (failed > 0) append("，失败${failed}项，已停止后续处理")
                    }
                )
                if (failures.isNotEmpty()) {
                    _messages.trySend(failures.take(3).joinToString("\n"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _messages.trySend("压缩已停止：${failure.message ?: "处理异常"}")
            } finally {
                compressionControl = null
                finishBatch(touched)
            }
        }
    }

    fun cancelCompression() {
        val control = compressionControl ?: return
        control.requestCancel()
        _batch.update { it?.copy(label = "正在取消", cancelling = true) }
    }

    fun restore(summary: SelectionSummary) {
        if (summary.empty || writing || _batch.value != null || _ui.value.scanning) return
        val targets = summary.doneTargets
        writing = true
        _batch.value = BatchState("正在还原", 0f, 0, targets.size)
        viewModelScope.launch {
            val touched = LinkedHashSet<String>()
            try {
                var done = 0
                var addedBytes = 0L
                var failed = 0
                _batch.value = BatchState("正在还原", 0f, 0, targets.size)
                for ((index, dm) in targets.withIndex()) {
                    touched += dm.record.dataPath
                    if (dm.record.originalPath.isNotBlank()) touched += dm.record.originalPath
                    when (val outcome = engine.restore(dm.record, onRestored = ledgerDao::deleteById)) {
                        is RestoreOutcome.Success -> {
                            touched += dm.record.dataPath
                            if (dm.record.originalPath.isNotBlank()) touched += dm.record.originalPath
                            done++
                            addedBytes += (dm.record.originalSize - dm.compressedSize).coerceAtLeast(0L)
                        }
                        is RestoreOutcome.Failed -> {
                            failed++
                            _messages.trySend("${dm.displayName}：${outcome.reason}")
                            break
                        }
                    }
                    _batch.value = BatchState("正在还原", (index + 1f) / targets.size, index + 1, targets.size)
                }
                clearSelection(AppPage.DONE)
                _messages.trySend(
                    buildString {
                        append("还原${done}项")
                        if (addedBytes > 0) append("，新增${formatSize(addedBytes).replace(" ", "")}")
                        if (failed > 0) append("，失败${failed}项")
                    }
                )
            } finally {
                finishBatch(touched)
            }
        }
    }

    fun clearSelection(page: AppPage) = updateLevel(page) {
        it.copy(pickedAlbums = emptySet(), pickedItems = emptySet())
    }

    // ---------------------------------------------------------------- 回收站

    fun purgeAllBackups() {
        if (writing || _batch.value != null || _ui.value.scanning) return
        val count = _ui.value.successfulBackups().size
        if (count == 0) return
        writing = true
        _batch.value = BatchState("正在清理备份", 0f, 0, count)
        viewModelScope.launch {
            try {
                // 清理不可撤销；开始后即使退出页面也完成删除及账本登记。
                withContext(NonCancellable) {
                    val protected = engine.pendingRecovery().flatMap { it.paths }.toSet()
                    val records = ledgerDao.findWithBackups().filter {
                        it.status in setOf(CompressedItemEntity.STATUS_DONE, CompressedItemEntity.STATUS_PURGED) &&
                            it.dataPath !in protected && it.originalPath !in protected
                    }
                    val result = engine.purgeBackups(
                        records,
                        allowMissingOrChangedMedia = true,
                        onPurged = { ledgerDao.markBackupsGone(it) },
                        onProgress = { done, total ->
                            _batch.value = BatchState("正在清理备份", done.toFloat() / total.coerceAtLeast(1), done, total)
                        },
                    )
                    _messages.trySend(buildString {
                        append(if (result.freedBytes > 0) "已清理备份，释放 ${formatSize(result.freedBytes)}" else "未释放空间")
                        if (result.protectedCount > 0) append("，${formatCount(result.protectedCount)} 份未完成恢复的备份保留")
                        if (result.failedCount > 0) append("，${formatCount(result.failedCount)} 份备份删除失败，可重试")
                    })
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _messages.trySend("备份清理未完成，请重试")
            } finally {
                finishBatch(emptyList())
            }
        }
    }

    /** 到期自动清理（F11）：删除超期备份，已压缩照片不受影响。 */
    fun purgeExpired() {
        if (writing || _batch.value != null || _ui.value.scanning) return
        writing = true
        viewModelScope.launch {
            try {
                val expired = ledgerDao.findExpired(System.currentTimeMillis())
                engine.purgeBackups(expired, onPurged = { ledgerDao.markBackupsGone(it) })
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _messages.trySend("到期备份清理未完成，请重试")
            } finally {
                finishBatch(emptyList())
            }
        }
    }

    // ---------------------------------------------------------------- 设置

    fun recoverOriginal(entry: RecoveryJournal.Entry) {
        if (writing || _batch.value != null || _ui.value.scanning) return
        writing = true
        _batch.value = BatchState("正在恢复原片", 0f, 0, 1)
        viewModelScope.launch {
            try {
                when (val result = engine.recover(entry, ledgerDao::deleteById)) {
                    is RestoreOutcome.Success -> _messages.trySend("原片已恢复，回滚完成")
                    is RestoreOutcome.Failed -> _messages.trySend(result.reason)
                }
            } finally {
                finishBatch(entry.paths)
            }
        }
    }

    fun setCompressPng(enabled: Boolean) {
        if (writing || _ui.value.scanning) return
        viewModelScope.launch {
            try {
                settingsDao.insertIfMissing(SettingsEntity())
                settingsDao.setCompressPng(enabled)
                clearSelection(AppPage.TODO)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _messages.trySend("PNG 设置保存失败：${failure.message}")
            }
        }
    }

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

    /** 图集过滤（F9/F10 的补充）：关闭的图集不出现在未压缩 / 已压缩页。 */
    fun setAlbumShown(name: String, shown: Boolean) {
        viewModelScope.launch {
            val current = settingsDao.get() ?: SettingsEntity()
            val set = current.excludedSet.toMutableSet()
            if (shown) set -= name else set += name
            settingsDao.upsert(current.copy(excludedAlbums = set.sorted().joinToString("\n")))
            // 此处刻意不发提示：开关的当前状态在列表里一眼可见，
            // 逐项切换时反复弹「已隐藏/已显示图集」纯属噪音。
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 回退完成后才开放新批次；缓存异常或生命周期取消也必须释放操作状态。 */
    private suspend fun finishBatch(touched: Collection<String>) {
        withContext(NonCancellable) {
            try {
                invalidateCache(touched)
                reloadLedger()
            } catch (failure: Throwable) {
                _messages.trySend("媒体状态更新失败：${failure.message}")
            } finally {
                writing = false
                _batch.value = null
            }
        }
        refresh()
    }

    private suspend fun reloadLedger() {
        val ledger = ledgerDao.observeAll().first()
        val recoveryState = withContext(Dispatchers.IO) {
            engine.pendingRecovery()
        }
        _ui.update { it.copy(ledger = ledger, recoveryEntries = recoveryState) }
    }

    fun refreshPermission() = _ui.update {
        it.copy(hasAllFilesAccess = StorageAccess.hasAllFilesAccess(getApplication()))
    }

    val recycleBinSize: Long get() = engine.recycleBinSize()

    companion object {
        private const val TAG = "PCCompress"
    }
}
