# 设备能力探针报告（阶段 1）

- 探针实现：`app/src/main/java/com/photocompress/app/core/diagnostics/DeviceCapabilityProbe.kt`
- 执行入口：`app/src/androidTest/java/com/photocompress/app/DeviceCapabilityProbeTest.kt`
- 执行命令：
  ```powershell
  .\gradlew.bat :app:installDebug :app:installDebugAndroidTest
  adb shell appops set com.photocompress.app MANAGE_EXTERNAL_STORAGE allow
  adb shell am instrument -w com.photocompress.app.test/androidx.test.runner.AndroidJUnitRunner
  adb logcat -d -s PCPROBE
  ```

## 测试环境

| 项 | 值 |
|---|---|
| 设备 | 模拟器 `sdk_gphone64_x86_64`（AVD `AndroidCompress_API36`，google_apis） |
| Android | API 36（Android 16） |
| ABI | x86_64, arm64-v8a |
| 采集时间 | 2026-10-04 |

> 目标真机为真我 GT7 Pro / ColorOS 16。模拟器**无法**验证 ColorOS 相册行为（U5 / U6 的相册侧接受度）与硬件编解码器差异，下表已逐项标注。

## 原始结论（logcat: PCPROBE）

```
encoder mime=video/av01 name=c2.android.av1.encoder hardware=false bitrate=[1, 20000000] size=[2, 1920]
encoder mime=video/avc  name=OMX.google.h264.encoder hardware=false bitrate=[1, 12000000] size=[16, 2048]
encoder mime=video/avc  name=c2.android.avc.encoder  hardware=false bitrate=[1, 12000000] size=[16, 2048]
encoder mime=video/hevc name=c2.android.hevc.encoder hardware=false bitrate=[1, 10000000] size=[2, 512]
encoder mime=video/mp4v-es name=c2.android.mpeg4.encoder hardware=false bitrate=[1, 64000] size=[16, 176]
encoderAvailable avc=true hevc=true vp9=false av1=true
inplace writable=true creatable=true birthTimeSupported=true birthTimePreserved=false
jpegmeta exif=true xmpStd=false xmpCustom=false
xmppacket rawRoundTrip=true customNamespace=true (xmpBytes=494)
mediaStoreTime inserted=true dateAddedPreserved=true dateTakenPreserved=true dateModifiedPreserved=true updatable=true
muxerHeif=true
```

## 逐项判定

### U4 视频编码器（可判定）

| 编码 | 模拟器可用 | 是否硬件 | 备注 |
|---|---|---|---|
| H.264 (avc) | 是 | 否（软件） | 最大 2048×2048，码率上限 12 Mbps |
| HEVC (hevc) | 是 | 否（软件） | **最大边长仅 512**，码率上限 10 Mbps，对本项目视频基本不可用 |
| VP9 | **否** | - | 无编码器 |
| AV1 (av01) | 是 | 否（软件） | 最大 1920，码率上限 20 Mbps |
| MPEG-4 | 是 | 否 | 最大 176，仅兜底 |

**结论与处置**：编码器选择必须在运行时按 `MediaCodecList` 探测结果决定，不得硬编码。
- 模拟器上视频重编码目标编码按 `源编码 → HEVC(若满足尺寸) → H.264` 回退；VP9 源在模拟器上无可用 vp9 编码器 → 按 C4 跳过并给出原因。
- 真机（GT7 Pro）预期具备 HEVC 硬件编码器，硬件能力差异属预期，不作为验收失败。
- 影响：D6 / F3 的「AV1 / VP9 编码」在模拟器上**无法完整验证**，仅在真机可确认。

### U1 直接文件写入（可判定）

`/sdcard/Download` 对 MANAGE_EXTERNAL_STORAGE 授权后的应用可写、可创建。
**结论**：D9（直接文件读写）在 Android 16 / API 36 上成立（模拟器）；真机 ColorOS 待复验。

### U2 原地截断写入与时间保留（**修正设计假设**）

- `BasicFileAttributes.creationTime()` 在 API 36 的 FUSE 挂载 `/sdcard` 上**可用**，但它映射到 inode **ctime**；原地截断写入后 ctime 变化 → **birth time 不保留**（实测 `before=1791124510000 after=1791124511000`）。
- 因此 design.md §4.1 中「复用同一 inode 即可保留创建时间」的假设在 API 36 上**不成立**。
- 但 MediaStore 的时间字段（`DATE_ADDED` / `DATE_TAKEN` / `DATE_MODIFIED`）在直接文件写入后**保持不变**，且可被本应用主动 `update` 写回（`updatable=true`）。

**修正后的时间保留方案（AC4 / F6）**：
1. 文件系统层：写入后用 `setLastModified` / `Os.utime` 还原 **mtime**。
2. 媒体库层：写入后按原值 `update` MediaStore 的 `DATE_ADDED`、`DATE_TAKEN`、`DATE_MODIFIED`（三者实测均可写）。
3. 「创建时间」对用户可见的语义 = 相册中的**拍摄时间（DATE_TAKEN）** 与 **加入时间（DATE_ADDED）**，二者由步骤 2 保证；FUSE birth time 无法保留，属平台限制，须在交付说明中如实标注。

### U6 文件内自有 XMP 标记（**修正设计假设**）

- `ExifInterface.setAttribute("XMP-<prefix>:<tag>", ...)` **不生效**（写后读回为 `null`），标准 XMP 亦不生效。
- 改用整包 XMP：`ExifInterface.setAttribute(ExifInterface.TAG_XMP, <xmpPacket 字符串>)` → 读回 `getAttributeBytes(TAG_XMP)` 成功，自有命名空间 `photocompress:PCId` 完整保留（494 字节）。

**修正后的 D5 / F7 方案**：自有标记以**完整 XMP 包**写入，命名空间 `urn:photocompress:1.0:meta`，字段 `PCId` / `PCVersion` / `PCTime`。
- 实况照片需在此包内**同时保留** oplus 的 `GCamera:*` / `OpCamera:*` 与 `Container:Directory`（由 `core.livephoto` 负责重组）。
- 相册对该命名空间的接受度属模拟器不可验证项（真机复验）。

### U3 HEIC 元信息保留（不可在模拟器完成，按 C4 跳过）

`MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF` 存在，但平台 API 无向 HEIF 写入 EXIF / XMP 的公开途径。
**抉择**：HEIC/HEIF 首版一律**识别并跳过**，给出原因「HEIF 元信息无法保留」，不修改原文件（C4 / AC12）。

### U5 实况照片相册识别（**模拟器不可验证**）

ColorOS 相册行为无法在 AOSP 模拟器上验证。容器重组正确性改为以**结构自校验**保证：
- XMP `Container:Directory` 的 `Item:Length` / `Padding` 重算后自洽；
- MP4 可被 `MediaExtractor` 打开、轨道数与时长与压缩前一致；
- 主图/GainMap 可被 `BitmapFactory` 解码。
上述通过后仍只在模拟器验证「文件结构可解析」，**相册识别 / 播放 / HDR 观感须真机复验**。

### U7 / U8 / U9

- U7（质量档位阈值标定）：可在模拟器对样张做客观指标对比（尺寸/PSNR/文件体积），"肉眼不可见" 最终判定须真机人工看图。
- U8（非相机格式跳过）：纯格式判定逻辑，模拟器可完整验证。
- U9（动图判别）：可在模拟器构造 GIF / 动态 WebP 验证判别逻辑。

## 对后续阶段的影响

1. **阶段 3 时间保留**改用「mtime 还原 + MediaStore 三字段写回」，不再依赖 birth time。
2. **D5 标记**改用整包 XMP（`TAG_XMP`），不再用 `XMP-xxx` 标签写法。
3. **阶段 4/5 编码器**运行时探测选择，模拟器上 HEVC 因 512 尺寸上限回退 H.264。
4. **HEIC** 按 C4 跳过。
5. 交付报告须区分「模拟器已验证」与「须真机复验」。

---

## 阶段 3–7 复验与修正（2026-10-04 晚）

在实现压缩/还原/实况照片/视频的过程中，对上面的初步结论做了进一步实测，**修正两处、补充三项**。

### 修正 1：MediaProvider 会静默丢弃时间字段的 update（重要）

初步结论中 `mediaStoreTime ... updatable=true` 具有误导性：`ContentResolver.update()` 返回行数 ≥ 0，但**值被丢弃**。logcat 明确告警：

```
W MediaProvider: Ignoring mutation of date_modified from com.photocompress.app
W MediaProvider: Ignoring mutation of datetaken from com.photocompress.app
W MediaProvider: Ignoring mutation of _size from com.photocompress.app
W MediaProvider: Ignoring mutation of date_added from com.photocompress.app
```

Android 11+ 起，非「媒体所有者」应用无法改写这几列。

**修正后的时间保留方案（已实现并验证）**：
1. 文件系统层：原地写入后用 `setLastModified` 还原 **mtime**（实测有效，`ls -l` 时间与压缩前一致）。
2. 媒体库层：`_size` 由 MediaProvider 的 FUSE 层在感知文件变化后自行重读，`DATE_MODIFIED` 由 mtime 派生；`DATE_TAKEN` 来自文件内 EXIF（我们逐字节保留 EXIF，因此该值稳定）。
3. `DATE_ADDED`（加入媒体库时间）无法由应用改写，会随 MediaProvider 的重新索引而变化。**这是平台限制**，须在交付说明中如实标注；不影响拍摄时间与文件时间。
4. App 自身的「未压缩 / 已压缩」统计不从 MediaStore 的 `_size` 取数，而是直接 `File.length()`，避免媒体库缓存滞后导致看板数字不准（AC9）。

### 修正 2：`OpCamera:VideoLength` 不是内嵌视频时长，必须原样保留

样张 `OpCamera:VideoLength=5116097`，但抽取内嵌 MP4 实测为 **1.73 s / 44 帧 / 1920×1440 HEVC**（`ffprobe`）。二者不符，说明该字段语义未知。早期实现曾按"时长(µs)"改写它，属于**错误假设**，已改为与其它 `OpCamera:*` 字段一样**原样保留、绝不改写**。

### 补充 1：视频编码器分辨率能力（模拟器）

| 编码器 | 宽范围 | 高范围 | 对齐 | 1920×1440 | 1920×1088 | 1280×960 |
|---|---|---|---|---|---|---|
| `c2.android.avc.encoder` | 16..2048 | 16..2048 | 2×2 | ✗ | ✓ | ✓ |
| `c2.android.hevc.encoder` | 2..512 | 2..512 | 2×2 | ✗ | ✗ | ✗ |
| `c2.android.av1.encoder` | 2..1920 | 2..1920 | 1×1 | ✗ | ✓ | ✓ |
| `c2.android.vp8.encoder` | 2..2048 | 2..2048 | 1×1 | ✓ | ✓ | ✓ |
| `c2.android.vp9.encoder` | 2..2048 | 2..2048 | 1×1 | ✗ | ✗ | ✓ |

HEVC 软件编码器上限仅 **512**，模拟器上对 720p/1440p 视频不可用。解码侧 `c2.goldfish.hevc.decoder`（硬件加速）与 `c2.android.hevc.decoder` 均可解 1920×1440。

**处置**：编码器与尺寸完全运行时探测；优先"保持原分辨率"的编码器，只有在设备确实无法编码原尺寸时才按等比**向下**搜索最大可编码尺寸（不放大）。模拟器上 1920×1440 的实况内嵌视频因此回退到 **1650×1238 H.264**；真机（GT7 Pro）具备 HEVC 硬件编码器，预期保持原尺寸原编码。

### 补充 2：VP9 无法用 MediaMuxer 封装进 MP4

以 VP9 为输出编码时 `MediaMuxer.addTrack` 抛 `IllegalStateException: Failed to add the track to the muxer`。因此**输出编码固定为 HEVC / H.264 / AV1**；VP9 仍可作为**输入**被正常读取与转码（实测 VP9 源 201 KB → H.264 143 KB）。

### 补充 3：oplus 实况照片的 MPF 偏移以 MP Endian 为基准（曾误判为"不自洽"）

样张 MPF 声明 2 张图（主图 + 增益图），MPF 段 payload 位于文件偏移 25,351，
其 TIFF 头（MP Endian "MM"）位于 payload +4 = **25,355**：

| | 原始文件 | 说明 |
|---|---|---|
| image0 | size=3,717,033 offset=0 | size 与实测主图长度 3,717,763 差 730（相机后处理过主图，属陈旧值） |
| image1 | size=430,057 offset=3,692,408 | **25,355 + 3,692,408 = 3,717,763 = 实测增益图起点，逐字节吻合** |

CIPA DC-007 规定 MPEntry 的 Individual Image Data Offset 是**相对 MP Endian（TIFF 头）位置**的偏移，
因此原始 MPF 的 offset 是自洽的；只有 image0.size 是陈旧值（相册显然不依赖它，否则原图也放不出来）。

**曾经的误判与后果**：早期按"offset 就是绝对偏移"理解，认为原 MPF 不自洽，
于是压缩后把 offset 写成**绝对位置**（主图长度）。这样写出的值比正确值大一个 MP Endian 基准
（约 25KB，随头部长度浮动），按 MPF 定位内嵌视频的读取方会整体偏移到视频中间，
**mp4 头 `ftyp` 丢失 → 实况照片无法播放**（真机反馈的"压缩后无法播放"即此）。

**处置**：`MpfRewriter.updateEntries` 增加 `offsetBase` 参数，写入 `绝对位置 − (MPF payload 位置 + 4)`；
首图按规范特例记 0。样张离线复算：基准 25,496，image1.offset=3,692,408，25,496 + 3,692,408 = 3,717,904
= 重组后增益图实际起点，吻合。

### 已实现的实况照片压缩口径（供真机复验）

1. 主图：按质量档位重编码 JPEG，EXIF 段**逐字节保留**（实测压缩前后 EXIF 段 23,654 字节完全相同）。
2. 增益图：**原样复制**，不重编码（HDR/ProXDR 重建依据，C6 / D10）。
3. 内嵌视频：**仅当尾段是「单个普通 MP4」时才重编码**；oplus/realme 的尾段是「MP4 + 私有块 + MP4」复合结构，
   此时**整段原样保留**（见补充 3 与真机修复记录），保证厂商结构不被破坏。
4. XMP：`Item:Length` 只在长度真的变化时就地改写数字；自有标记以属性形式注入；`GCamera:*` / `OpCamera:*` 原样保留。
5. MPF：重建 MPEntry，offset 以 **MP Endian 位置为基准**写入。
6. 结构自校验：Container 项齐全且长度非 0、内嵌 MP4 开头为 `ftyp`、解码可解析。

样张实测（保留厂商尾段口径）：**12,478,846 → 10,015,856 字节（-19.7%）**，EXIF / 增益图 / 内嵌尾段逐字节一致。

