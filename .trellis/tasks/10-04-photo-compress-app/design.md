# 技术设计：安卓照片压缩 App

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
| `core.jpeg` | 静态图解码 / 按档位重编码（JPEG / PNG / WebP / BMP / AVIF）、EXIF / XMP / IPTC 搬运、alpha 保留、动图判别 |
| `core.heif` | HEIC 解码 / 重编码与 HEIF 元信息（高风险，见 §8 U3） |
| `core.livephoto` | 实况照片容器解析与重组（主图 / GainMap / MP4 三段） |
| `core.mp4` | MP4 box 级解析、样本索引重建、重封装、偏移重算 |
| `core.video` | 视频重编码（MediaCodec 编解码与码率控制） |
| `core.rewrite` | 原地原子改写、时间戳恢复、回滚 |
| `data.ledger` | 压缩账本、唯一编号、回收站索引 |
| `ui` | 首页看板 / 未压缩页 / 已压缩页 / 设置页 / 回收站 |
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
| `status` | PENDING / COMPRESSING / DONE / FAILED / RESTORED / PURGED |
| `failureReason` | 跳过或失败原因（C4 / AC12） |

其余表：`media_scan_cache`（未压缩项缓存：大小、类型、mtime）、`settings`（三类质量档位）。

## 4. 关键流程

### 4.1 原地改写（核心，最高风险）

目标：路径不变、创建时间不变、修改时间不变、断电安全。

1. **先备份**：原文件复制到应用私有目录 `files/recycle/<uuid>/`，记录 `backupRelPath` 与原文件 SHA-256。
2. **同目录生成结果**：压缩产物写到同目录临时文件 `<name>.pc.tmp`（同卷，保证后续可原地搬移）。
3. **校验**：容器可解析、内嵌引用（长度 / 偏移）自洽、元信息字段逐项比对、解码抽样对比；任一项不通过即放弃本次压缩。
4. **原地写入**：用 `RandomAccessFile("rw")` 打开**原文件本身**并写入压缩字节，再 `truncate()` 到新长度。
   - 关键点：复用同一 inode，文件系统 `birth time`（创建时间）自然保留——Android Java 层无法直接设置创建时间，这是满足 F6 的唯一可靠手段。
5. **恢复修改时间**：用 `Os.utime` / `Files.setLastModifiedTime` 写回原 mtime。
6. **同步媒体库**：`MediaScannerConnection.scanFile` 或按 uri 更新 SIZE / DATE_MODIFIED，使相册立即看到新大小。
7. **失败回滚**：任一步失败 → 从备份恢复原文件（同样原地写回）→ 删除临时文件 → 记录 `FAILED` + 原因。

> 与 `MediaStore` 直接 `update` 的取舍：直接改文件字节可完整保留 `birth time` 与媒体库行 ID；`update` 方式会重建文件、丢失创建时间，故不采用。

### 4.2 静态图片（JPEG / PNG / WebP / BMP / AVIF）

- 解码 → 按档位重编码，保持像素尺寸与色彩空间。
- **PNG 带 alpha 时必须保留透明度**；长截屏（最高 1080×100000）需分块解码，避免 OOM。
- 元信息以原文件为基准整体搬运，只更新必要字段（尺寸等）。
- 含 GainMap（MPF / Ultra HDR）的图片需同步重编码并按 MPF 规范重建各段偏移（D10，普通照片同样适用）。
- **动图判别**：GIF、动态 WebP、动态 AVIF 一律跳过（不在首版范围，AC14）。
- 若结果体积 ≥ 原体积 → 跳过并记录原因，不改动文件。
- **未决**：PNG / BMP 是无损格式，要获得有损收益必须改变容器格式（见 §8 U8 与 Q11）。方案确定前不在代码中固化格式转换行为。

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

### 4.6 MP4 容器重写（内嵌视频与独立视频共用）

- box 级解析（`ftyp` / `moov` / `mdat` / `stbl` 等），重建样本索引与 chunk 偏移。
- 保留未知 box 与轨道元信息。
- 完整读回校验通过后才允许进入 §4.1 的替换步骤。

## 5. 去重与唯一编号（F7 / C2）

- 生成 UUID v4 作为唯一编号。
- 双写：Room 账本 + 压缩后文件内 XMP 自有命名空间字段（编号 + 压缩器版本 + 时间）。
- 判定"已压缩"：优先读文件内标记（跨重装有效），其次查账本；两者不一致时以文件内标记为准并记录告警。
- 依赖真机验证新增 XMP 字段的接受度（U6）。

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
| U5 | 实况照片重组后相册识别 / 播放 / HDR | AC1 |
| U6 | 新增自有 XMP 字段是否被相册与其他应用接受 | D5 / F7 |
| U7 | JPEG 质量档位"肉眼不可见"的阈值标定 | C1 / AC2 |
| U8 | PNG / BMP 为无损格式，有损压缩需转容器格式（扩展名与 MIME 会变）；AVIF 在 Android 平台**无公开编码器**（`Bitmap.compress` 只支持 JPEG/PNG/WebP） | Q11 / F1 / D6 / AC14 |
| U9 | 长截屏（超长 PNG）的解码内存与分块策略 | AC14 |
| U10 | 动图判别（GIF / 动态 WebP / 动态 AVIF）的可靠实现 | AC14 |

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
