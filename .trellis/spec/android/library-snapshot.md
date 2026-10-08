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

## 回归检查

`LibrarySnapshotTest` 使用内存媒体，断言首次扫描的两页分类、数量、占用、图集和设置页名单；排除图集后仍能在名单中重新开启；PNG 开关立即更新候选；媒体/账本/恢复日志/设置过期结果拒绝；导航/选择/进度保留；空库刷新清除旧结果。

构建成功不能替代这些断言。真机界面核对扫描结束后的首页统计与设置图集名单时，不触发压缩/还原或清空应用数据。
