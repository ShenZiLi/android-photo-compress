# 压缩期间屏幕常亮

- `BatchState.keepScreenOn` 默认关闭，仅在 `AppViewModel.compress()` 创建压缩批次时开启；还原、清理备份和扫描不启用。
- 进度、取消、回滚及“正在更新”的批次状态通过 `copy` 保留常亮标记。`finishBatch()` 在收尾的 `finally` 中清空批次，成功、失败和取消均释放常亮。
- `AppEntry` 使用 `collectAsStateWithLifecycle` 观察批次，以 `DisposableEffect(view, keepScreenOn)` 管理 `LocalView.current.keepScreenOn`；退出效果时恢复接管前的值，避免永久常亮。
- 保持屏幕常亮只作用于应用可见窗口，允许系统在应用后台时正常熄屏，用户仍可手动锁屏。不申请唤醒锁、不修改系统屏幕超时设置。

## 验证

完成调试构建、Lint 与差异检查；按项目约定不新增或运行测试套件。设备验收使用独占媒体副本，确认超过系统屏幕超时仍亮屏，完成/失败/取消后恢复自动熄屏，以及旋转、切后台、返回前台和手动锁屏的行为。未实测时明确记录限制。
