# 实施计划：安卓照片压缩 App

关联：[prd.md](./prd.md)（需求与验收）、[design.md](./design.md)（技术设计）。

> 实施结果与逐条验收结论见 [research/acceptance-report.md](./research/acceptance-report.md)；
> 真机/模拟器能力实测见 [research/device-capability-report.md](./research/device-capability-report.md)。

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

## 阶段 3：静态图压缩 + 原地改写 + 回收站（核心闭环）

- [ ] `core.jpeg`：JPEG 按档位重编码 + EXIF / XMP / IPTC 搬运 + GainMap / MPF 重建。
- [ ] `core.rewrite`：备份 → 临时文件 → 校验 → 原地截断写入 → 恢复时间 → 扫描媒体库 → 失败回滚。
- [ ] `data.ledger`：账本、唯一编号、XMP 标记双写。
- [ ] 未压缩页多选批量压缩；已压缩页批量还原；回收站清理。
- [ ] 验证：AC3 / AC4 / AC5 / AC6 / AC8 / AC9 / AC10 / AC13 真机通过；AC2 在样张上完成 U7 标定。

## 阶段 4：实况照片

- [ ] `core.mp4`：box 解析与样本索引重建。
- [ ] `core.livephoto`：XMP `Container:Directory` 解析与重组，长度 / 偏移重算。
- [ ] 接入内嵌视频重编码（依赖阶段 5 的编码能力，可先直通再替换）。
- [ ] 验证：AC1 真机通过（识别为实况、可播放、HDR 正常）。

## 阶段 5：视频

- [ ] `core.video`：MediaCodec 重编码（保持分辨率 / 帧率 / 时长），音频直通。
- [ ] 独立视频接入未压缩页与批量流程。
- [ ] 验证：AC7 视频部分通过；无可用编码器时按 C4 跳过并给出原因。
- [x] **阶段 5b：10bit HDR「保真或跳过」（D11 修订，2026-10-05）**
  - [x] `VideoProbe` 扩展 profile / bitDepth / colorRange，新增 `HdrKind` / `HdrFidelity`。
  - [x] 兜底探测：`scanHevcConfig`（hvcC）+ `scanColrInfo`（colr），覆盖「Main10 但无 colr」源。
  - [x] `canPreserveHdr`（进程级缓存）能力门槛，判类阶段即拦截无 Main10 编码器的 HDR 源。
  - [x] `HdrMode.PRESERVE`：HEVC Main10 固定、不降分辨率、HDR 专用码率系数、`ColorPatch` 写 signaling 与 HDR10 静态元数据。
  - [x] 产物校验 `isHdrPreserved`（含 colr 兜底）+ `compressVideo` 二次保险；失败即跳过、原文件零改动。
  - [x] **删除 `HdrMode.SDR_CLEAR` 及自动降级链**（禁止 HDR 静默转 SDR 落地）。
  - [x] 文档同步：`prd.md` D11/C5/AC12/不在首版范围、`design.md` §4.5.1/§8 U4b·U4c、`device-capability-report.md`、`acceptance-report.md` §六。
  - [x] **真机 U4b 已验证（2026-10-05）**：realme RMX5010（GT7 Pro）/ ColorOS 16 具备 HEVC Main10 硬件编码器 `c2.qti.hevc.encoder`（profiles 含 2 / 4096 / 8192，上限 8192）→ **保真压缩路径可用**，HDR 源不会被迫跳过；4K HDR 可原分辨率编码。
  - [ ] 验证：编译通过；模拟器跳过路径待跑；**真机 U4c 待验**（产物 `colr`/`hvcC` 一致性 + 相册观感）。

## 阶段 6：扩展格式（按阶段 1 与 U3 / U4 / U8 / U9 结论决定是否纳入）

- [ ] 跳过判别：PNG、BMP、WebP、AVIF、GIF / 动态 WebP / 动态 AVIF、MOV/MKV/WebM/3GP/TS 视频识别并跳过，并显示原因。
- [ ] HEIC：验证 HEIF 内 EXIF / XMP 是否可保留；不可保留则按 C4 跳过。
- [ ] AV1 / VP9 视频：有硬编码器才启用，否则跳过并说明。
- [ ] 验证：AC12 / AC14 通过（跳过项不改动原文件且有原因）。

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
- 静态图判类：JPEG/HEIC 之外（PNG、BMP、WebP、AVIF、GIF / 动态 WebP）识别并跳过。
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

## 2026-10-07：静态双图 MPF（0.1.23）

- [x] 增加真实 JPEG EOI 扫描与严格 MPF TIFF/条目解析，容忍主图 size 陈旧和辅助图既有填充。
- [x] 接入静态双图主图压缩，完整搬运辅助图、间隔、填充和厂商尾部；重算 MPF 主图长度/辅助图偏移。
- [x] 复用图片质量档位、XMP 唯一标记、持久恢复记录与安全原地写入。
- [x] 两份用户样本副本通过 API 36 原生 APP 压缩，分别减少 58.3% / 53.0%；HDR、Display P3、增益参数、元数据和日期身份读回一致。
- [x] 在原生 APP 中还原两份备份，文件逐字节恢复，路径、修改时间、媒体库 ID/日期一致。
- [ ] 真机 HDR 显示观感与文件创建时间验收（由用户在真机验证，未以虚拟机结果替代）。

样本记录与边界见 [research/mpf-dual-photo.md](research/mpf-dual-photo.md)。本次未新增或运行单元测试。

## 2026-10-07：无收益图片归类（0.1.24）

- [x] 统一无体积收益提示，删除尺寸括号。
- [x] 普通图片/实况无收益时只登记 SKIPPED，原片不写、无备份、零节省。
- [x] 两页共用有效记录规则；已压缩页提供已跳过标记/筛选，图集与信息面板不冒充超期或已清理。
- [x] 合成副本原生压缩、分页、重启、筛选与混合图集还原验收通过；原片摘要、身份和时间读回一致。
- [x] 调试/正式构建与虚拟机覆盖安装成功；未新增或运行单元测试。

边界与证据见 [research/no-size-reduction.md](research/no-size-reduction.md)。
