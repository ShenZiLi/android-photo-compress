# 媒体错误恢复与原片保护契约

## 1. Scope / Trigger

适用于 JPEG、实况、MP4 的原地压缩、还原、取消、回收站清理及旧版 HEIC 转换还原。出现写入、相册日期、数据库或进程中断错误时，必须保留可校验的原始副本及可到达的恢复入口。HEIC 无法保持原格式、路径和完整元数据时按 C4 跳过，不再以删除 HEIC 后转 JPEG 的方式落地。

## 2. Signatures

- `RecoveryJournal.begin(Entry)`：原文件改写前以 AtomicFile 持久写入 `files/recovery/<id>.json`。
- `RecoveryJournal.entries(): List<Entry>` / `finish(id)`：读取未完成事务；只有完整提交或确认回退完成后移除入口。
- `CompressionEngine.restore(record, onRestored)`：先校验备份，写入前保存当前版本；文件和媒体库通过后调用账本回调，再清理正常备份。
- `CompressionEngine.recover(entry, onRecovered)`：扫描时自动重试或从未压缩详情重试，回调传 `Entry.ledgerId`；完整恢复后清理本次已校验备份与安全副本。
- `CompressionEngine.reconcileRolledBack(records, onFailed, onRemoved)`：只有当前源文件与记录原片大小/摘要一致时修正旧假 DONE；不改写原片。只有时间、媒体身份和副本证据通过才清理，否则保留 FAILED 记录及备份。
- `UiState.successfulBackups()`：回收站唯一取项口径。`PurgeResult(freedBytes, failedCount, protectedCount)` 分开真正删除错误与保护跳过。
- `CompressionEngine.untrackedBackups(records)` / `exportUntracked(records)`：旧版无索引备份只能导出为新图集，不自动删除。
- `MediaStoreUpdater.scanExisting(context, path)` / `pointsTo(context, uri, path)`：验证存在文件和扫描结果，不通过删除 URI 重建索引。

## 3. Contracts

恢复记录字段：`id/path/backup/sha256/mtime/uri/taken/added/modified/safety/ledgerId/takenNull`。压缩写前从媒体库读取真实日期，takenNull 区分原始 null 与 UI 的时间回退值。mtime 为 FileTime 字符串，压缩取消恢复保留纳秒精度；原始备份 SHA-256 必须在恢复前及恢复后验证。安全副本保存还原前的当前版本。

同一待恢复路径禁止再次压缩。恢复记录不可读或损坏时失败关闭，禁止清理备份。回收站保护恢复记录引用的原始/安全备份，并在现有媒体缺失或大小变化时保留可能唯一剩下的备份。无账本备份不得视为可删除垃圾。

批处理遇到 Failed 停止后续处理并显示错误，已完成历史不回退。Skipped 可继续。失败撤销账本独立于相册同步，撤销失败不能阻止字节回滚；未完成时恢复记录使原 DONE 和文件内新标记不参与已压缩归类。完整回滚后清理本次备份，再清除入口；若进程恰在备份已清理但入口尚在时中断，源文件已匹配原始摘要可安全继续收尾。未恢复完整的条目在未压缩页显示，无媒体条目时根据原恢复记录展示占位，禁用再次压缩并允许详情重试。

压缩、还原、恢复、旧记录修正和备份清理共用进程 Mutex；扫码恢复期间界面写操作禁用，避免保护检查与备份删除并发。还原不清除备份自带的 XMP/MPF 字段，恢复内容以原始摘要为准。无收益 SKIPPED 与处理 Failed 是不同状态。

## 4. Validation & Error Matrix

| 故障 | 文件与备份行为 | 用户结果 |
| --- | --- | --- |
| HEIC 不满足原格式完整性 | 原片不写、不删除，不产生 JPEG 替代品 | 不支持并保留原片 |
| 备份、摘要或恢复记录保存失败 | 原文件不改写 | 失败并停止后续项 |
| 改写后媒体同步或账本失败 | 非取消恢复原始字节，分别校验文件、时间、媒体库 | 完整恢复或明确告知相册/日期未通过 |
| 回退仍失败或写入中进程中断 | 原始备份及恢复入口持久保留，禁止到期和手动清理 | 未压缩页“处理失败”，详情重试恢复 |
| 还原备份损坏 | 覆盖前拒绝 | 当前照片保持 |
| 还原写入失败 | 先保存当前版本，失败时尝试从该版本回退；原始备份仍受保护 | 可继续恢复，禁止误报成功 |
| 原片已写回但日期/账本失败 | 保留已恢复原片、备份及恢复入口；旧版 JPEG 转换图也保留 | 明确标明原片恢复与同步状态 |
| 历史无账本备份 | 原始备份不删，产品中的找回板块已移除 | 不假定原路径，不冒充自动还原；保留私有原始副本 |
| 普通失败完整回滚 | 原片、日期/身份与账本恢复，本次备份和恢复入口清理 | 原片仍在未压缩页，不新增回收站内容 |

## 5. Good / Base / Bad Cases

- Good：完整写回、媒体同步与账本通过后结束事务；正常还原完成后清理安全副本。
- Base：日期校验失败但字节已恢复；继续保留备份并停止批处理。
- Bad：原文件缺失、备份摘要不符、损坏的恢复记录、别名 URI 指向同一文件、旧版未登记备份。均禁止以清理或重新压缩补救。

## 6. Tests Required

针对 `MediaSafetyTest`，只用测试创建的独占 URI/文件和明确提供的 HEIC 私有缓存副本。断言：HEIC SHA/inode/mtime 不变；同步失败原片摘要不变；数据库失败回退不变；取消只回退当前事务；坏备份不覆盖；还原失败保留原片和备份并可重试；进程中断模拟后恢复原始字节与时间；旧 HEIC/JPEG 双文件均保留；唯一备份不被 purge 删除。原厂相册及设备兼容性仍需单独验收。

0.1.26 更新既有保护计数与完整恢复后备份清理断言，未新增或运行测试套件。API 36 原生故障验收：SQLite INSERT 触发器真实抛错后，原片摘要/inode/mtime不变，无 DONE、无本次备份或恢复记录；旧假 DONE 修正；中断原片字节恢复，日期受外部故障影响时不误报完整成功；坏备份不覆盖可用 JPEG；未知副本保留，回收站无找回/失败卡片。细节见 [回滚验收](../../tasks/10-04-photo-compress-app/research/rollback-workflow.md)。

## 7. Wrong vs Correct

- 错误：扫描新路径后比较 URI 字符串不同就 delete(oldUri)。正确：只扫描并校验文件路径；外部和主卷 URI 可能别名，删除媒体行会连带删除照片。
- 错误：把未登记备份当残留直接清空。正确：未登记文件可能是失败后唯一原片，留存并提供导出。
- 错误：先删除备份，再写入账本或只校验字节长度。正确：先校验原始摘要、完成文件/媒体/账本，再清理。
