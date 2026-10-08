# 媒体库派生快照发布

适用：`AppViewModel.rebuildLibrary`、`LibrarySnapshot`，以及媒体、账本、恢复日志或设置更新后的派生结果。

## 签名与输入契约

`LibrarySnapshot.applyTo(state: UiState): UiState` 在 `MutableStateFlow.update` 内发布。新快照的 `items`、`ledger`、`recoveryEntries` 按引用与当前输入比较，`settings` 按值比较。列表输入必须通过新列表替换，不能原地修改；页面、层级、选择、扫描进度不属于快照输入。

## 发布行为

| 当前状态 | 行为 |
| --- | --- |
| 新快照匹配当前输入，旧快照过期 | 发布新快照，包括首次扫描从空库变非空库 |
| 新快照匹配输入，期间仅导航/选择/进度改变 | 发布并保留当前其他状态 |
| 新快照任一输入已过期 | 原样返回当前状态，等待对应输入更新的重建结果 |
| 扫描结果为空 | 发布空快照，清除旧列表、图集和汇总 |

不能拿旧 `state.library` 判断新计算结果是否有效，否则输入变化时会拒绝新结果、旧结果反而可能覆盖最新快照。

```kotlin
// 错误：检查旧快照
if (state.library.matches(state)) state.copy(library = rebuilt) else state
// 正确：检查即将发布的新快照
if (rebuilt.matches(state)) state.copy(library = rebuilt) else state
```

## 回收站备份列表不经过快照

`UiState.successfulBackups()` 直接从 `ledger` 与 `recoveryEntries` 派生（`backupRelPath != null`、状态属于 DONE/PURGED、排除恢复日志涉及的路径），`LibrarySnapshot` 不再承载备份列表。

原因：整库快照要遍历全部媒体项，排在清理收尾会让回收站列表落后于账本；备份列表与媒体项无关，直接过滤账本（真机 2790 条账本为微秒级）才能在账本写入后立即反映。真机事故形态：Toast 已提示「已清理备份，释放 19.39 GB」，列表与计数仍停在旧值，而库内 `backupRelPath IS NOT NULL` 已是 0。

新增或改动备份相关视图时，不得把 `library`（或其派生结果）当作备份的事实来源。

## 账本实时订阅与清理收尾

- `AppViewModel.observeLedger()` 订阅 `ledgerDao.observeAll()`：先立即更新 `ledger`，再等待 250ms 合并调用 `rebuildLibrary()`。清理按每 64 项写一次账本，逐批刷新让列表随清理推进递减；整库遍历必须合并，否则一批清理会触发数十次整库重算。
- `finishBatch(touched, rescan)` 的 `rescan = false` 用于清理回收站：备份位于应用私有目录，不改动 MediaStore 内容，重扫既无收益又会占用 CPU 与 IO、拖慢紧随其后的界面刷新。压缩、还原、恢复原片路径继续 `rescan = true`。

## 回归检查

`LibrarySnapshotTest` 使用内存媒体，断言首次扫描的两页分类、数量、占用、图集和设置页名单；排除图集后仍能在名单中重新开启；PNG 开关立即更新候选；媒体/账本/恢复日志/设置过期结果拒绝；导航/选择/进度保留；空库刷新清除旧结果；**备份列表在快照尚未重建时即由账本给出、账本清空备份后立即为空、恢复日志涉及的路径不进入回收站**。

构建成功不能替代这些断言。真机界面核对扫描结束后的首页统计与设置图集名单时，不触发压缩/还原或清空应用数据。
