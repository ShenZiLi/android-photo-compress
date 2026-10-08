# 回收站清理后备份列表即时刷新

## Goal

清理备份完成后，回收站列表与「N 份备份可还原」数量立即反映账本结果，不再出现「Toast 已提示释放空间、列表仍显示旧条目」的滞后。

## 现象与取证

真机 RMX5010（已装 0.1.38）清理备份后，Toast「已清理备份，释放 19.39 GB」已显示，而回收站列表与计数仍停在旧值。

拉取应用私有库核对：

| 项 | 实测 |
| --- | --- |
| `backupRelPath IS NOT NULL` | 0 |
| 状态分布 | PURGED 2751 / SKIPPED 39 |
| `files/recycle` | 已空 |
| 设置页 / 回收站页 | 0 份 / 回收站是空的 |
| 媒体库规模 | 8832 项（LIVE_PHOTO 2816 / PHOTO 5957 / VIDEO 59） |

结论：账本写入正确，稳态界面也一致；问题是「清理完成瞬间」的刷新时机。

## Requirements

1. 回收站列表与数量只依赖账本与恢复日志，不再等待整库派生快照重建。
2. 账本变更（清理、还原、后台到期清理 Worker）自动进入界面；清理过程中列表逐批减少。
3. 清理备份的收尾不再触发与备份无关的全库重扫——备份位于应用私有目录，不改动相册内容。
4. 不改变既有语义：清理后仍为「已压缩」/PURGED、已压缩页照常显示、超期与恢复保护逻辑不变。

## Acceptance Criteria

- [x] 清理备份后，回收站列表在账本写入后立即变为空态、计数为 0，无需等待整库快照与重扫。
      `LibrarySnapshotTest.recycleBackupsFollowLedgerInsteadOfDerivedSnapshot` 断言「快照尚未重建时备份列表已由账本给出」「账本清空备份后立即为空」。
- [x] 清理过程中列表随批次推进逐步减少，不再出现「完成瞬间整体滞后」——`observeLedger()` 订阅账本逐批生效，整库重建按 250ms 合并。
- [x] 后台到期清理写账本后，界面读取的备份数量与账本一致——同一账本订阅驱动。
- [x] 清理不再触发 `refresh()` 全库扫描；压缩、还原路径收尾行为不变——`finishBatch(rescan = false)` 仅用于 `purgeAllBackups` 与 `purgeExpired`。
- [x] `LibrarySnapshot` 不再承载备份列表，`LibrarySnapshotTest` 全绿。
- [ ] 真机界面复现「清理 1 份备份后列表立即清空」——**未执行**：本次按用户选择不做复现，未在真机造备份数据；以编译、真机 instrumented 测试与应用冒烟作为替代证据。

## 验证记录

- `./gradlew --offline :app:assembleDebug` → BUILD SUCCESSFUL。
- 真机 RMX5010（90d3e7c6）执行 `am instrument -w -e class com.photocompress.app.ui.LibrarySnapshotTest` → `OK (8 tests)`，含本次新增 2 条断言。
- 安装 0.1.39 debug 后启动应用：进程存活、无新增崩溃。

## Constraints

- 备份列表的过滤条件必须与 `LibrarySnapshot.backups` 完全一致：`backupRelPath != null`、状态属于 DONE/PURGED、排除恢复日志涉及的路径。
- 整库快照重建涉及整库遍历（真机 8832 项媒体 + 2790 条账本），禁止逐批触发。

## Notes

- 真机取证与命令见 `.workbuddy/memory/2026-10-08.md`。
- 技术设计见 `design.md`。
