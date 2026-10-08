package com.photocompress.app.ui

import android.app.ActivityManager
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
                rebuildLibrary()
            }
        }
        refresh()
    }

    /**
     * 重建全库派生结果。
     *
     * 派生结果只随媒体库 / 账本 / 恢复日志 / 设置变化，与页面和选中项无关；
     * 计算放到默认调度器，页面切换的首帧因此不再承担整库重算（过渡动画掉帧的根因）。
     * 输入没变时 [LibrarySnapshot.matches] 直接命中，不重复计算。
     */
    private suspend fun rebuildLibrary() {
        val source = _ui.value
        if (source.library.matches(source)) return
        val rebuilt = withContext(Dispatchers.Default) {
            LibrarySnapshot(source.items, source.ledger, source.recoveryEntries, source.settings)
        }
        // 期间又发生了新的媒体库/账本变更时丢弃本次结果，由那次变更自己的重建接手。
        _ui.update { if (it.library.matches(it)) it.copy(library = rebuilt) else it }
    }

    // ---------------------------------------------------------------- 扫描

    fun refresh() {
        if (!StorageAccess.hasAllFilesAccess(getApplication())) return
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
                rebuildLibrary()
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
        // 并发度只取决于「加速压缩」开关与当前可用内存；开关关闭时维持逐项串行。
        // 视频不走并发池：硬编码器实例有限，多路同时转码会互相抢占，反而更慢甚至失败，
        // 因此视频始终单线程，与开关无关。
        val parallelism = if (_ui.value.settings.fastCompress) fastCompressParallelism() else 1
        val concurrent = parallelism > 1 && targets.count { it.kind != MediaKind.VIDEO } > 1
        _batch.value = BatchState(
            if (concurrent) "正在压缩 · $parallelism 路并发" else "正在压缩",
            0f, 0, targets.size, canCancel = true,
        )
        viewModelScope.launch {
            val touched = LinkedHashSet<String>()
            try {
                val tiers = _ui.value.settings
                var done = 0
                var savedBytes = 0L
                var skipped = 0
                var failed = 0
                var finished = 0
                var stopped = false
                // 同一批次按首次出现顺序收集不同原因，统计仍按实际项目计数。
                val failures = linkedSetOf<String>()
                // 并发下结果统计与进度更新共用一把锁，保证批次状态单调一致。
                val resultLock = Mutex()
                // 图片并发池：路数由开关与可用内存决定。
                val gate = Semaphore(parallelism)
                // 视频独占池：任何时刻至多一个视频在转码，与并发开关无关。
                val videoGate = Semaphore(1)
                coroutineScope {
                    // 单任务执行体：图片走并发池、视频走串行池，共用同一套结果统计与进度更新。
                    val runOne: suspend (MediaItem) -> Unit = { item ->
                        if (!control.isCancellationRequested && !resultLock.withLock { stopped }) {
                            // 并发时名字会来回跳，只在串行（含视频）时展示当前项。
                            if (parallelism <= 1 || item.kind == MediaKind.VIDEO) _batch.update { it?.copy(currentName = item.displayName) }
                            val tier = tierFor(item.kind, tiers)
                            // 实况照片内嵌视频取独立的「实况视频段」档位；只有普通视频才用「视频」档位
                            val videoTier = if (item.kind == MediaKind.VIDEO) {
                                QualityTier.fromName(tiers.videoTier)
                            } else {
                                QualityTier.fromName(tiers.liveVideoTier)
                            }
                            val outcome = engine.compress(
                                item, tier, videoTier, control,
                                onCommit = ledgerDao::upsert,
                                onRollback = ledgerDao::deleteById,
                                pngEnabled = tiers.compressPng,
                            )
                            resultLock.withLock {
                                finished++
                                when (outcome) {
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
                                        // 错误可能来自存储或媒体库：保留已完成项，不再启动新项，已在跑的项目自然收尾。
                                        stopped = true
                                    }
                                    is CompressOutcome.Cancelled -> {
                                        touched += item.dataPath
                                        outcome.reason?.let { failed++; failures += it }
                                        stopped = true
                                    }
                                }
                                _batch.update {
                                    it?.copy(progress = finished.toFloat() / targets.size, done = finished, currentName = null)
                                }
                            }
                        }
                    }
                    targets.map { item ->
                        async {
                            // 并发关闭（或内存不足退化为 1 路）时全部走同一把锁，保持逐项串行；
                            // 并发开启时视频才分流到独占池，图片与视频互不阻塞、视频自身仍串行。
                            if (parallelism > 1 && item.kind == MediaKind.VIDEO) videoGate.withPermit { runOne(item) }
                            else gate.withPermit { runOne(item) }
                        }
                    }.forEach { it.await() }
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

    /** 加速压缩：只改并发度，档位与产物不变，代价是发热与内存占用上升。 */
    fun setFastCompress(enabled: Boolean) {
        if (writing || _ui.value.scanning) return
        viewModelScope.launch {
            try {
                settingsDao.insertIfMissing(SettingsEntity())
                settingsDao.setFastCompress(enabled)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _messages.trySend("加速压缩设置保存失败：${failure.message}")
            }
        }
    }

    /**
     * 图片压缩的并发路数（视频不占用这些路数：视频固定单线程）。
     *
     * 单张全尺寸位图按 ARGB_8888 计约 48 MB（12 MP）到 192 MB（48 MP），
     * 路数必须跟着可用内存走，否则大图批量压缩会把内存打爆。
     */
    private fun fastCompressParallelism(): Int {
        val manager = getApplication<Application>().getSystemService(ActivityManager::class.java) ?: return 2
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            info.availMem > 2L * 1024 * 1024 * 1024 -> minOf(3, cores)
            info.availMem > 1L * 1024 * 1024 * 1024 -> minOf(2, cores)
            else -> 1
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
                _messages.trySend("相册状态更新失败：${failure.message}")
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
        rebuildLibrary()
    }

    fun refreshPermission() {
        val granted = StorageAccess.hasAllFilesAccess(getApplication())
        val newlyGranted = granted && !_ui.value.hasAllFilesAccess
        // 先解除授权页，再异步扫描；首次全库扫描不能阻挡进入首页。
        _ui.update {
            it.copy(
                hasAllFilesAccess = granted,
                page = if (newlyGranted) AppPage.HOME else it.page,
            )
        }
        if (newlyGranted) refresh()
    }

    val recycleBinSize: Long get() = engine.recycleBinSize()

    companion object {
        private const val TAG = "PCCompress"
    }
}
