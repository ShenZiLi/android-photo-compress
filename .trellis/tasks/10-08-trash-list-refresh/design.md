# 设计：备份列表脱离整库快照

## 现状链路

- `UiState.successfulBackups()` → `LibrarySnapshot.backups`，在整库快照构造时算一次。
- `AppViewModel.finishBatch()` 收尾：读账本 → `rebuildLibrary()`（`Dispatchers.Default` 上整库遍历）→ `refresh()`（全库扫描）。
- `ledgerDao.observeAll()` 只在 `refresh()` / `reloadLedger()` 里用 `.first()` 拉一次，没有实时订阅。

即：列表刷新排在「整库快照重建」之后，收尾还要多跑一次与备份无关的全库扫描，叠加成可感知的秒级滞后。

## 改动

### 1. 备份列表脱离快照

`UiState.successfulBackups()` 直接由 `ledger` + `recoveryEntries` 过滤，删除 `LibrarySnapshot.backups`。

依据：备份列表只依赖账本与恢复日志，与媒体项 `items` 无关。解耦后一次账本更新（对 2790 条账本的微秒级过滤）即可刷新界面。

### 2. 账本实时订阅 + 合并重建

`AppViewModel` 新增 `observeLedger()`：

```kotlin
ledgerDao.observeAll().collectLatest { records ->
    _ui.update { it.copy(ledger = records) }
    delay(LIBRARY_REBUILD_DEBOUNCE_MS)
    rebuildLibrary()
}
```

依据：清理按每 64 项写一次账本，逐批刷新可让列表实时减少；整库遍历必须合并，否则 43 批会触发 43 次整库重建。

### 3. 清理收尾不重扫

`finishBatch(touched, rescan: Boolean = true)`；`purgeAllBackups()` 与 `purgeExpired()` 传 `rescan = false`。

依据：备份在 `files/recycle`（应用私有目录），清理不改动 MediaStore 内容，重扫无收益且会占用 CPU / IO。

## 兼容与回滚

- 过滤条件与 `LibrarySnapshot.backups` 完全一致，`TrashScreen` / `SettingsScreen` 调用点不变。
- 压缩、还原、恢复原片路径继续 `rescan = true`，行为不变。
- 回滚：`successfulBackups()` 恢复为 `library.backups`，移除 `observeLedger()`，恢复 `finishBatch` 签名。

## 风险

- `observeLedger()` 首次发射会与 `refresh()` 的重建重复一次：幂等，且 `applyTo` 的输入一致性检查保证旧结果不会覆盖新结果。
- 账本更新与整库重建之间存在 250ms 窗口，界面持新账本 + 旧快照：只影响备份相关视图，而备份视图已改为账本派生。
