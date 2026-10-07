# 无体积收益图片的持久状态

## 1. 范围

用户明确要求：提示只显示“压缩后体积未减小”；此类普通图片/实况图片离开未压缩页，放入已压缩页标记“已跳过”。其他不支持、校验失败、取消不进入该状态。视频仅统一无收益提示，本次不修改其归类。

## 2. 接口与字段

- `CompressOutcome.Skipped(reason, noSizeReduction = false)`；`Skipped.noSizeReduction()` 返回固定文案及类型标记，不以字符串匹配推断处理策略。
- `CompressedItemEntity.STATUS_SKIPPED` / `skipped`：持久状态。Room 仍为 v5，不增列、不清库。
- `CompressionEngine.skippedRecord(item, tier, size, modified)`：记录 UUID、媒体 ID/卷/路径、原片大小/摘要/日期和处理时间。
- `Attempt.publishUnchanged(record)`：登记前检查取消，提交在不可取消上下文完成；不改写原文件，不调用备份或媒体刷新。
- `UiState.activeLedger()`：两页共用的有效记录范围；`DoneMedia.skipped`、`AlbumDoneUi.statusLine`、`applyDoneFilter("skipped")` 决定展示。

## 3. 状态契约

SKIPPED 记录的 `originalSize == compressedSize`，`backupRelPath = null`，`backupSize = 0`，`restoreDeadlineMs = 0`，`savedBytes = 0`，`restorable = false`，`failureReason = 压缩后体积未减小`。唯一 ID 由类型前缀、卷、媒体 ID、路径生成，重复登记更新同一条记录。

跳过不往原文件写 XMP、不创建压缩备份，原始字节、位置和日期不变。状态由本地账本维持，重启和扫描不丢失；清除应用数据后不承诺仍能识别跳过。

只有媒体仍存在、ID/卷/路径/大小/媒体修改日期一致且无压缩标记时，SKIPPED 才参与归类。文件变化或缺失不能以历史跳过记录继续隐藏。实际 DONE/PURGED 和标记接管行为保持原有规则。

## 4. 验证与错误

| 情况 | 结果 |
|---|---|
| JPEG、双图 MPF 或实况最终输出无收益 | 原片不写，持久 SKIPPED，继续下一项 |
| 文件在处理期间大小或修改时间变化 | 拒绝登记，提示失败；原片不写 |
| 取消发生在登记前 | Cancelled，不新增跳过记录 |
| 登记已经完成后点击取消 | 本项保留为已跳过，下一项不开始 |
| 数据库登记失败 | Failed，停止后续项；原片仍完整，无额外回退写入 |
| HEIC/PNG 不支持、结构校验失败 | 普通 Skipped，不能伪装为已处理 SKIPPED |

## 5. 展示案例

- 已压缩图集状态行仅显示“可还原 N 项”（N > 0）或“备份已不可还原”（N = 0），不显示“已跳过 M 项”；不可还原文案统一使用警示色。
- 纯跳过图集：一级显示“备份已不可还原”和当前体积；二级正常显示原片，标记“已跳过”，无还原勾选框。
- 混合图集：只显示“可还原 N 项”；全选、确认和还原只包含真正有备份的项目。
- 信息面板：处理状态“已跳过”、固定跳过原因、处理时间、备份状态“原片未改动，无需还原”。不能显示“已超期”或“已清理”。
- 已超期筛选排除 SKIPPED；已跳过筛选只取 SKIPPED。

## 6. 验收断言

2026-10-07，0.1.24 API 36 原生 UI，专门生成的合成媒体：低质量 JPEG 无收益进入 SKIPPED，高质量 JPEG 成功进入 DONE，PNG 无处理记录。低质量 JPEG 和 PNG 摘要、inode、纳秒 mtime、媒体库身份/日期不变。SKIPPED 无备份、无节省，DONE 有真实备份，重启后仍归类正确；筛选和信息面板文案正确。还原混合图集仅处理成功的 JPEG，恢复字节与原片一致；剩余图集显示一项已跳过。

开发者未要求测试套件，本次不新增或运行单元测试；构建与原生交互验收、实际产物核对单独记录。未来专项验证应覆盖记录失效、取消时序、数据库失败，不能将未执行的边界验证标为通过。

## 7. 常见错误

错误：把所有 Skipped 都放到已压缩页，或在 UI 临时隐藏无收益项目。正确：只为明确的无收益图片持久登记 SKIPPED，两页共用有效记录规则。

错误：给原片写“已压缩”XMP、伪造 DONE 或备份到期时间。正确：独立 SKIPPED 状态，源文件不改，节省为零且无需还原。
