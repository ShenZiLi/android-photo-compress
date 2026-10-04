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
