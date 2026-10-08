# 技术设计：安卓照片压缩 App

## 2026-10-05 实况原厂播放修复边界

真机实际应用 `com.photocompress.app` 对应本工程。已用新建副本重现原地压缩后 `_size/o_video_size` 缓存陈旧；在 GT7 Pro / Android 16 上，显式 `IS_PENDING=0` 可强制扫描同一已发布条目，正确读回实际文件大小和实况段长度。用户先确认 PC-OEM 对照图能播放，随后确认 0.1.1 真机修复成功。已发布状态始终为 0，不做 pending=1 隐藏/改名，不删除或重建原媒体条目。

实施范围为 `core.rewrite/MediaStoreUpdater`、压缩失败的恢复结果报告及专用设备回归。刷新成功必须读回实际尺寸、实况段长度与原媒体日期/路径/ID，不吞异常或以 update 影响行数冒充完成。设备回归只写新生成并检查来源的测试副本；用户授权修复的两个故障条目也已单项再发布并核验文件校验值、inode、修改时间、媒体日期及路径保持。原始备份不改动。完整流程分别覆盖压缩和还原后的刷新。

详细结论见 [实况相册同步修复记录](research/livephoto-store-refresh-fix.md)。媒体日期及刷新前后文件修改时间的检查不能替代完整文件创建时间验收，后者仍按项目完整性要求单独验证。

关联需求见 [prd.md](./prd.md)。本文件只写技术设计，不重复需求条目编号的含义。

## 1. 技术栈与工程基线

- 语言 / UI：Kotlin + Jetpack Compose（Material 3 / Material You），单模块 `app`。
- 构建：Gradle Kotlin DSL + Version Catalog；`minSdk 30` / `targetSdk 36` / `compileSdk 36`。
- 本机可用：JDK 17、Gradle 8.14.5、SDK `C:\Users\Admin\AppData\Local\Android\Sdk`（`android-36`、build-tools 36.1.0、platform-tools、NDK 30）。AGP 版本按 Gradle 8.14 的兼容矩阵选定（实施时确认）。
- 持久化：Room（压缩账本与设置）。
- 后台：前台服务（长任务）+ WorkManager（回收站到期清理）。
- 媒体处理：平台 MediaCodec / MediaMuxer / HeifWriter + 自研 JPEG 元信息与 MP4 容器处理。
- 元信息：androidx.exifinterface 处理 EXIF；自研 XMP 读写（需覆盖 GainMap 与 oplus 私有命名空间）。

## 2. 模块边界

| 模块 | 职责 |
|---|---|
| `data.media` | MediaStore 枚举、媒体分类、文件定位与权限状态 |
| `core.jpeg` | JPEG 解码 / 按档位重编码、EXIF / XMP / IPTC 搬运、GainMap / MPF 重建、不支持格式判别 |
| `core.heif` | HEIC 解码 / 重编码与 HEIF 元信息（高风险，见 §8 U3） |
| `core.livephoto` | 实况照片容器解析与重组（主图 / GainMap / MP4 三段） |
| `core.mp4` | MP4 box 级解析、样本索引重建、重封装、偏移重算 |
| `core.video` | 视频重编码（MediaCodec 编解码与码率控制） |
| `core.rewrite` | 原地原子改写、时间戳恢复、回滚 |
| `data.ledger` | 压缩账本、唯一编号、回收站索引 |
| `ui` | 首页看板 / 未压缩（图集→图片二级）/ 已压缩（图集→图片二级）/ 设置页 / 回收站；图片长按信息面板 |
| `worker` | 前台服务 + 任务队列（批量、进度、取消、失败原因） |

原则：所有会改动用户文件的代码只允许出现在 `core.rewrite`，其余模块只产出字节流或临时文件。

## 3. 数据模型（Room）

`compressed_item`

| 字段 | 说明 |
|---|---|
| `id` (UUID, PK) | 唯一编号（D5） |
| `mediaStoreId` / `dataPath` / `volumeName` | 媒体库定位 |
| `mediaKind` | PHOTO / LIVE_PHOTO / VIDEO |
| `mimeType` / `containerFormat` / `videoCodec` | 压缩后实际格式 |
| `originalSize` / `compressedSize` / `savedBytes` | 账本与看板数据（F8 / AC9） |
| `originalSha256` | 备份一致性校验与还原校验 |
| `qualityTier` / `codecUsed` | 本次使用的档位与编码 |
| `compressedAt` / `restoreDeadline` | `compressedAt + 30 天`（D1 / F10） |
| `backupRelPath` / `backupSize` | 应用私有回收站中的原文件位置 |
| `status` | PENDING / COMPRESSING / DONE / FAILED / RESTORED / PURGED / SKIPPED（无收益原片，未改写） |
| `failureReason` | 跳过或失败原因（C4 / AC12） |

其余表：`media_scan_cache`（未压缩项缓存：大小、类型、mtime）、`settings`（三类质量档位）。

**图集维度**：两页的一级列表是图集（MediaStore bucket / 相册），因此账本与扫描缓存都需带 `bucketId` / 图集名，并支持按图集聚合（项数、大小、可压缩数、可还原数）。图集级批量操作要对"未被逐张展示的其余媒体"同样生效，不能只处理已加载的缩略图。
聚合口径必须自洽：图集项数 = 图集内全部媒体数，可还原数 ≤ 项数；勾选统计、按钮文案与确认文案使用同一个来源。

## 4. 关键流程

### 4.1 原地改写（核心，最高风险）

目标：路径不变、创建时间不变、修改时间不变、断电安全。

1. **先备份**：原文件复制到应用私有目录 `files/recycle/<uuid>/`，记录 `backupRelPath` 与原文件 SHA-256。
2. **同目录生成结果**：压缩产物写到同目录临时文件 `<name>.pc.tmp`（同卷，保证后续可原地搬移）。
3. **校验**：容器可解析、内嵌引用（长度 / 偏移）自洽、元信息字段逐项比对、解码抽样对比；任一项不通过即放弃本次压缩。
4. **原地写入**：用 `RandomAccessFile("rw")` 打开**原文件本身**并写入压缩字节，再 `truncate()` 到新长度。
   - 复用同一 inode，避免更换文件对象；但**阶段 1 实测：API 36 的 FUSE 挂载下 `creationTime` 映射到 inode ctime，原地写入后必然变化**，因此 birth time 无法保留（见 `research/device-capability-report.md`）。
5. **恢复时间**：
   - 文件系统层：`setLastModified` / `Os.utime` 写回原 **mtime**（实测有效）。
   - 媒体库层：`_size` 由 MediaProvider 自行重读；`DATE_MODIFIED` 由 mtime 派生；`DATE_TAKEN` 来自文件内 EXIF（EXIF 逐字节保留，故稳定）。
   - `DATE_ADDED` 无法由非媒体所有者应用改写（MediaProvider 会忽略并告警），属平台限制，如实标注。
6. **同步媒体库**：按 uri 更新 SIZE 并保持时间字段；不触发全量重扫（重扫会把 `DATE_ADDED` 重置为扫描时刻）。
7. **失败回滚**：独立撤销本次成功登记，数据库异常不能阻止原片字节恢复；从已校验备份原地写回、核对摘要、纳秒修改时间、真实媒体日期与身份。完整通过后清理本次备份/恢复记录。未完成恢复进入未压缩页的失败状态，持久保护并允许详情重试；不以普通回收站条目或无登记副本代替事务回滚。媒体操作使用进程共享 Mutex，避免回滚/还原与到期清理并发删除同一备份。

> 与 `MediaStore` 直接 `update` 的取舍：直接改文件字节可保留媒体库行 ID 与 `DATE_TAKEN`；
> 而 `_size` / `date_modified` / `datetaken` / `date_added` 这几列的 `update` 会被 MediaProvider 静默忽略。
> App 自身的统计一律直接读 `File.length()`，不依赖媒体库缓存。

### 4.2 JPEG 普通照片

- 解码 → 按档位重编码 JPEG，保持像素尺寸与色彩空间。
- 元信息以原文件为基准整体搬运，只更新必要字段（尺寸等）。
- 多图 MPF / Ultra HDR 使用 `MpfPhotoContainer`：按真实 JPEG EOI 提取外层主图，逐项建立全部 MPEntry 辅助图列表，按原声明范围检查 JPEG、区间与依赖编号。重编码主图后完整搬运其后缀，按同一长度差平移所有辅助图并相对新 TIFF 基准写回（D10 / D14 / D16）。不递归重编码 Original 或辅助图，不改内层相对索引；三图以上实况的视频与厂商尾部也原样保留。不能从文件末尾倒推带原始图/厂商尾部的增益图位置。
- **跳过判别**：BMP、WebP、AVIF、GIF / 动态 WebP / 动态 AVIF 跳过并给出原因（D12）。PNG 由默认关闭的设置开关控制，开启后按普通照片档位转 JPEG，使用专用备份、同媒体改名及原 PNG 恢复事务；细节见 [PNG 转换](research/png-jpeg.md) 和 [编码规范](../../spec/android/png-jpeg.md)。
- 若结果体积 ≥ 原体积 → 提示“压缩后体积未减小”，不改动文件，登记 SKIPPED 并移入已压缩页（D15）。使用现有账本字段，原始与当前大小相等、无备份、零节省；共享有效记录规则决定两页归类，图片变化或缺失时旧记录失效。

### 4.3 HEIC 普通照片（高风险）

- 解码 HEIC → 重编码 HEIF（MediaCodec HEVC + MediaMuxer `MUXER_OUTPUT_HEIF`，或 `HeifWriter`）。
- 风险：平台 API 无法把 EXIF / XMP 写入 HEIF，直接违背 F5。
- **门槛（U3）**：真机验证能否在 HEIF 内无损保留 EXIF / XMP。不能保留则 HEIC 项标记"暂不支持压缩"并跳过（C4 / AC12），绝不放宽元信息要求。

### 4.4 实况照片

- 解析 XMP：`Container:Directory` 三 Item（Primary / GainMap / MotionPhoto）与 `OpCamera:*` 字段。
- 按 §4.2 重编码主图与 GainMap；按 §4.5 重编码内嵌 MP4；`GCamera:MotionPhotoPresentationTimestampUs` 保持。
- 重组：主图 + GainMap + MP4 顺序拼接，重算 `Item:Length` / `Item:Padding`、`OpCamera:VideoLength`，并检查 MP4 内部偏移。
- 走 §4.1 原地改写。
- 真机验证：相册识别为实况、可播放、HDR 正常（AC1）。

### 4.5 视频

- MediaCodec 解码 / 编码；保持分辨率、帧率、时长不变，只调整编码与码率。
- 音频默认直通（AAC 原样）；仅在容器不兼容时重编码为 AAC。
- 优先硬编码器；目标编码（AV1 / VP9）无可用硬编码器时回退 H.264 / HEVC 或跳过（C4）。
- 重封装后保留轨道时间戳与媒体库时间字段。

#### 4.5.1 10bit HDR：保真或跳过（D11）

**核心原则：HDR 源要么保真压缩，要么跳过；禁止静默降级为 SDR 落地。**

判定（`VideoProbe` / `VideoProbeRunner`）：
- HDR 判定 = 传递特性 ∈ {PQ 6、HLG 7、PQ-ISO 16、HLG-ISO 18} ∪ 色彩标准 = BT.2020(6) ∪ HEVC Main10 系(profile 2/4096/8192) ∪ 位深 ≥ 10。
- **兜底链**：MediaExtractor 不报告 `KEY_PROFILE` / `bit-depth-luma` 时，直读 `hvcC` 取 profile 与位深
  （`VideoProbeRunner.scanHevcConfig`）；不报告色彩键时，直读 `colr`(nclx) 取 primaries/transfer/matrix
  （`VideoProbeRunner.scanColrInfo`）。二者覆盖「10bit Main10 但无 colr」的裸 HDR 源。

能力门槛（`MediaClassifier.canPreserveHdr`，进程级缓存）：
- 设备存在 HEVC 编码器且 `profileLevels` 含 Main10 系之一 → 可保真；否则在**判类阶段**即标记跳过，
  理由「无 HEVC Main10 编码器，无法保真压缩 HDR（xx），已跳过」，不进入压缩流程。

保真压缩（`HdrMode.PRESERVE`）：
- 编码 MIME 固定 HEVC，**不接受降分辨率**（`selectEncoder(requireMain10 = true)` 不做缩放）。
- 码率系数独立放宽：HIGH 0.85 / BALANCED 0.62 / COMPACT 0.45。
- signaling 在 `MediaMuxer.addTrack` **之前**打补丁（`ColorPatch`）：`KEY_COLOR_STANDARD`(BT.2020)、
  `KEY_COLOR_TRANSFER`(PQ/HLG)、`KEY_COLOR_RANGE`、`KEY_HDR_STATIC_INFO`（HDR10 静态元数据 / clli）；
  解码器 `INFO_OUTPUT_FORMAT_CHANGED` 时若源格式未带静态元数据则从解码器输出格式补齐。
- 源未标注任何色彩信息、仅靠 profile/位深判为 HDR 时，按 BT.2020 + PQ 补齐 signaling，保证产物可识别。

产校与兜底：
- 压缩后 `isHdrPreserved` 逐项比对（传递特性、10bit 主档、BT.2020），并在报告缺省时直读产物 `colr`。
- 校验未通过或转码失败 → **跳过**（`Result(success = false)`），`CompressionEngine` 删除临时产物、
  返回 `Skipped` 且**不触碰原文件**；`compressVideo` 另有二次确认（产物必须 `isHdr`）后置保险。
- 落地的 HDR 结论写入账本 `codecUsed`，格式 `"HEVC · HDR 保真（HDR10/PQ）"`；实况内嵌视频同理写入 `motionNote`。

### 4.6 MP4 容器重写（内嵌视频与独立视频共用）

- box 级解析（`ftyp` / `moov` / `mdat` / `stbl` 等），重建样本索引与 chunk 偏移。
- 保留未知 box 与轨道元信息。
- 完整读回校验通过后才允许进入 §4.1 的替换步骤。

#### 4.6.1 相机元数据搬运（`Mp4Metadata`）

MediaMuxer 只写编码必需的结构，源 `moov` 下承载相机信息的框必须显式搬回，
否则表现为「压缩后相册不显示相机信息」。两个必须遵守的要点：

- **用排除法，不用白名单**：搬运源 `moov` 下**除 `mvhd` / `trak` / `mvex` / `iods` /
  `drm` / `pssh`（产物自有）之外的全部子框**。写成「只搬 `udta` / `meta`」会漏掉
  厂商私有框——实测 realme 在 `moov` 下放了 `titl`，一漏就直接丢。
- **同名子框一律以源为准**：不能因为「产物已有同名框」就跳过。MediaMuxer 会自己写一个
  约 118B 的空 `moov/meta`，一跳过就把源里带相机键的 `meta`（实测 447B）顶掉了。

配套约束：

- `mvhd` 不替换，改为 `patchMvhdTime` 同步创建 / 修改时间（时长语义以产物为准）。
- `moov` 变大导致 `mdat` 后移时，同步平移 `stco` / `co64`；
  MediaMuxer 默认把 `moov` 写在文件末尾，此情形一般不需要平移。
- 回归保护：`VideoMetadataPreservedTest`（真机跑完整链路、逐字节比对）、
  `Mp4MetadataTest`（JVM 单测，覆盖同名框顶替与厂商私有框）。

## 5. 去重与唯一编号（F7 / C2）

- 生成 UUID v4 作为唯一编号。
- 双写：Room 账本 + 压缩后文件内 XMP 自有命名空间字段（编号 + 压缩器版本 + 时间）。
- 写入方式：androidx ExifInterface 不支持 `XMP-xxx` 形式的 setAttribute（阶段 1 实测写后读回为 null），
  改为写入**整包 XMP**（`ExifInterface.TAG_XMP`），合并时保留包内其它命名空间
  （实况照片的 `GCamera:*` / `OpCamera:*` / `Container:Directory` 必须原样留下）。
- 判定"已压缩"：优先读文件内标记（跨重装有效），其次查账本；两者不一致时以文件内标记为准并记录告警。

## 6. 回收站与还原（F10 / F11 / D1）

- 备份存放：`files/recycle/<uuid>/`。
- 还原：用备份原地写回媒体文件（复用 §4.1 的写入与时间恢复逻辑），校验 SHA-256 后状态置 `RESTORED` 并删除备份。
- 到期清理：WorkManager 每日扫描，`now > restoreDeadline` 且状态为 `DONE` 的备份执行 `PURGED`。
- 还原限制：仅压缩时间 + 30 天内的记录可还原（AC10）。

## 7. 权限与运行

- `MANAGE_EXTERNAL_STORAGE`（D9）为首要路径，直接文件读写。
- 前台服务承载批量压缩 / 还原，展示进度与取消入口。
- 不申请网络权限（无云端功能）。

## 8. 必须在真机验证的技术未知项

| 编号 | 未知项 | 影响的决策 / 验收 |
|---|---|---|
| U1 | ColorOS 16 是否允许对"非本应用创建"的媒体直接文件写入（MANAGE_EXTERNAL_STORAGE 实际生效范围） | D9 成立与否；决定是否需要回退 MediaStore 授权 |
| U2 | 原地截断写入后，相册是否正常显示并保留归属 / 时间 | AC3 / AC4 |
| U3 | HEIC 重编码能否保留 EXIF / XMP | F1 / D6 / AC12 |
| U4 | 设备是否存在 AV1 / VP9 硬件编码器 | F3 / D6 |
| U4b | **设备是否存在 HEVC Main10（HDR10）编码器**（`DeviceCapabilityProbe.probeHevcMain10`，日志 `hevcMain10Encodable=`）——决定 10bit HDR 视频能否保真压缩 | D11 / AC12 |
| U4c | **HDR 保真链路的真机复验**：PRESERVE 产物 `colr`/`hvcC` 的 10bit + PQ/HLG + BT.2020 是否一致，相册观感是否发灰 | D11 / AC12 |
| U5 | 实况照片重组后相册识别 / 播放 / HDR | AC1 |
| U6 | 新增自有 XMP 字段是否被相册与其他应用接受 | D5 / F7 |
| U7 | JPEG 质量档位"肉眼不可见"的阈值标定 | C1 / AC2 |
| U8 | 非相机原生静态图（PNG / BMP / WebP / AVIF）一律跳过，不做格式转换；AVIF 在 Android 平台无公开编码器 | D12 / AC14 |
| U9 | 动图判别（GIF / 动态 WebP / 动态 AVIF）的可靠实现 | AC14 |

验证结论必须落盘到 `research/`（如 `research/device-capability-report.md`），不作为口头结论。

## 9. 关键权衡

- **直接文件读写（D9）**：换取时间与原地替换的精度和可控性，代价是不能上架 Play、需要较重权限。
- **内嵌视频重编码（D7）**：换取实况照片的真实收益，代价是耗时与兼容风险。
- **HEIC / AV1 / VP9（D6）**：覆盖最全，代价是首版真机验证成本显著上升；用 C4 的"宁可跳过"策略兜底。
- **原地截断写入 + 私有备份**：兼顾时间保留与断电安全，代价是压缩期间需要与原文件等量的临时空间。

## 10. 兼容性与迁移

- 项目无历史实现，无迁移负担。
- 账本与文件内标记双写；崩溃恢复以文件内标记为准。
- 不满足条件的媒体不修改原文件，状态与原因可查（AC12）。

## 2026-10-07：PNG 候选状态随开关联动

`UiState.todoItems()` 是未压缩页图集、网格、筛选、选择和底部统计的统一入口。PNG 的候选状态每次由当前 `settings.compressPng` 决定，覆盖扫描缓存中的转换预检判定：开启即 Supported，关闭即“PNG 压缩未开启”。待恢复事务分支保持最高优先级，不能用开关解除失败事务保护。

可勾选表示允许发起处理；是否能安全转 JPEG 仍由 `CompressionEngine.compressPng` / `PngCompressor.compress` 在任何原片写入前决定。维持透明、动画、位深、色彩与元数据保护，不修改媒体改名/还原事务，也不因设置切换全量重扫。

## 2026-10-07：HEIC 原格式编码与容器保留

采用 `core.heif.HeicCompressor` + `HeicContainer`，AndroidX HeifWriter 编码临时 HEIC，复用原 HEIF 元数据与引用结构重建主图数据位置。直接主图或 grid 编码布局、色彩及非主图载荷通过后，复用现有 commit/备份/回滚/还原；原路径和后缀不变。写前源 SHA 与备份匹配，写回结果 SHA 与临时文件匹配，自有 UUID 承载 XMP 标记，扫描恢复旧缓存分类。HEVC CQ 独立使用 75/55/35。

实现范围和验收边界见 [HEIC 规范](../../spec/android/heic.md) 与 [原格式验收](research/heic-native.md)。不重新启用历史 HEIC→JPEG 转换。

## 2026-10-07：确认后删除成功备份

`purgeBackups` 增加默认 false 的 `allowMissingOrChangedMedia`。手动清空确认后传 true，且仅 DONE/PURGED 记录豁免当前媒体存在/大小检查；自动清理仍走原检查。恢复引用检查在豁免之前执行，实际删除继续使用 private recycle 路径校验及 checked deletion，删除成功才批量清空备份索引。更新现有确认弹窗与剩余保护项文案，不增加另一轮弹窗或处理个人媒体。

## 2026-10-08：HEIC dinf 本文件引用

样例 meta 含 `dinf/dref/url `：dref v0 flags0、一个 url v0 flags1，iloc 的 data_reference_index 为 0。扩充 HeicContainer 元结构允许集，只接纳经过边界与数量校验的本文件 URL 引用表；非零 iloc 引用只允许有效本文件条目，Location 保留原引用索引，重组写回同值。原 dinf 字节和其他未改写 meta 子框在输出读回时逐字节核对；未知扩展提示列出实际 fourcc。不放开未知结构或外部引用，不改变 HDR/辅助图/变换/网格限制。无需修改图片分类、UI、账本或媒体事务。
