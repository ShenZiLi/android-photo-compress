# 实施计划：安卓照片压缩 App

关联：[prd.md](./prd.md)（需求与验收）、[design.md](./design.md)（技术设计）。

分阶段推进，每个阶段结束都是一个可独立提交、可独立回滚的变更单元（遵守 `spec/android/git-workflow.md`）。

## 阶段 0：工程脚手架

- [ ] 初始化 Gradle 工程（Kotlin DSL + Version Catalog），单模块 `app`，`minSdk 30 / targetSdk 36 / compileSdk 36`。
- [ ] Compose + Material 3 依赖与主题（动态取色、深浅色）。
- [ ] 依赖：Room、WorkManager、androidx.exifinterface；图片缩略图方案（Coil）。
- [ ] Manifest：`MANAGE_EXTERNAL_STORAGE`、前台服务类型、通知权限；不申请网络权限。
- [ ] 验证：`gradlew :app:assembleDebug` 通过。

## 阶段 1：真机能力探针（先做，决定后续路径）

- [ ] 实现诊断入口：枚举 MediaCodec 编解码器（HEVC / H.264 / AV1 / VP9）、报告是否可对一个非本应用创建的媒体文件直接写入并读回。
- [ ] 在真我 GT7 Pro 上安装并运行，覆盖 design.md §8 的 U1 / U4。
- [ ] 产出 `research/device-capability-report.md`（结论落盘，不口头传递）。
- [ ] 若 U1 不成立 → 暂停阶段 3~5，先回到规划调整 D9。

## 阶段 2：媒体库与界面骨架

- [ ] MediaStore 枚举照片 / 实况照片 / 视频，区分"未压缩 / 已压缩"。
- [ ] 首页看板（F8）、未压缩页（F9）、已压缩页（F10）、设置页（F12）骨架。
- [ ] 验证：页面统计与系统相册的数量、大小一致（AC9 前置）。

## 阶段 3：JPEG 压缩 + 原地改写 + 回收站（核心闭环）

- [ ] `core.jpeg`：按档位重编码 + EXIF / XMP / IPTC 搬运。
- [ ] `core.rewrite`：备份 → 临时文件 → 校验 → 原地截断写入 → 恢复时间 → 扫描媒体库 → 失败回滚。
- [ ] `data.ledger`：账本、唯一编号、XMP 标记双写。
- [ ] 未压缩页多选批量压缩；已压缩页批量还原；回收站清理。
- [ ] 验证：AC3 / AC4 / AC5 / AC6 / AC8 / AC9 / AC10 真机通过；AC2 在样张上完成 U7 标定。

## 阶段 4：实况照片

- [ ] `core.mp4`：box 解析与样本索引重建。
- [ ] `core.livephoto`：XMP `Container:Directory` 解析与重组，长度 / 偏移重算。
- [ ] 接入内嵌视频重编码（依赖阶段 5 的编码能力，可先直通再替换）。
- [ ] 验证：AC1 真机通过（识别为实况、可播放、HDR 正常）。

## 阶段 5：视频

- [ ] `core.video`：MediaCodec 重编码（保持分辨率 / 帧率 / 时长），音频直通。
- [ ] 独立视频接入未压缩页与批量流程。
- [ ] 验证：AC7 视频部分通过；无可用编码器时按 C4 跳过并给出原因。

## 阶段 6：HEIC / AV1 / VP9（按阶段 1 与 U3 / U4 结论决定是否纳入）

- [ ] HEIC：验证 HEIF 内 EXIF / XMP 是否可保留；不可保留则按 C4 跳过。
- [ ] AV1 / VP9：有硬编码器才启用，否则跳过并说明。
- [ ] 验证：AC12 通过（跳过项不改动原文件且有原因）。

## 阶段 7：收尾

- [ ] 回收站 30 天到期自动清理（WorkManager）。
- [ ] 设置持久化与三类档位独立生效（AC11）。
- [ ] 失败原因与进度展示、界面打磨。
- [ ] 验证：AC11、AC12 复测；全量 AC 回归。

## 验证命令

```powershell
# 构建
gradle :app:assembleDebug
# 单元测试（JPEG 元信息搬运 / MP4 box 重建 / XMP 长度重算 / 原地改写与回滚）
gradle :app:testDebugUnitTest
# 真机安装
& "C:\Users\Admin\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

单元测试重点（可在无真机时先验证）：

- MP4 box 解析与样本索引重建（`core.mp4`）。
- XMP `Container:Directory` 深度解析与 `Item:Length` / `Padding` 重算（`core.livephoto`）。
- JPEG EXIF / XMP 搬运的字段级一致性与 MPF 偏移重建（`core.jpeg`）。
- 原地改写的中断回滚（备份 → 写入 → 模拟中断 → 恢复）。

## 风险文件与回滚点

| 风险点 | 风险 | 处置 |
|---|---|---|
| `core.rewrite` | 原地写入破坏用户原文件 | 必须先备份再写；写入失败自动恢复；单测覆盖中断场景 |
| `core.livephoto` / `core.mp4` | 容器重组错误导致相册无法识别 | 完整读回校验后才允许替换；真机验证后再进入下一步 |
| `core.heif` | 元信息丢失风险 | 按 C4 直接跳过，不做降级妥协 |
| 整阶段 | 阶段失败 | 每阶段一个提交，失败回滚该阶段提交 |

## 启动前检查（`task.py start` 之前）

- [ ] `implement.jsonl` 与 `check.jsonl` 已含真实规范条目。
- [ ] 真机与 adb 可用（阶段 1 探针必需）。
- [ ] 除 `实况图片.jpg` 外，额外准备样本：HEIC 照片、HDR 照片、多编码视频、多厂商实况照片。
- [ ] 用户已评审 `prd.md`、`design.md`、`implement.md` 并明确批准进入实施。
