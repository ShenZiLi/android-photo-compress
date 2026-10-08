# 压缩耗时优化（2026-10-08）

## 已有策略

1. JPEG：`qualityFor` 为 92/85/76，`compressJpeg` 只有一次 `Bitmap.compress`，原尺寸解码。
2. 视频：`selectEncoder` 第一轮选择支持原分辨率的硬件编码器；`transcodeOnce` 使用 Surface 连接解码器与编码器。设备能力不足时沿用现有回退；HDR 仍只允许 Main10 保真或跳过。
3. 减少重编码：音频直接复用编码样本，GainMap、MPF 附加图与厂商尾部原样搬运。

## 本轮实际缺口

- 普通 JPEG 被识别为实况后，`compressLivePhoto` 再次读取同一源文件。
- MPF/实况主图在原数组之外创建完整主图副本后才解码。
- 实况 GainMap 另建副本；新视频与尾部先相加，再复制到最终输出流。
- 视频编码器虽然已使用 lazy 枚举，选择函数仍为每次尝试重新排序。
- 音频两条复制路径直接把 Extractor 标记传给 Codec BufferInfo。既有 Lint 报告有两处 `WrongConstant`；本机 Android SDK 36.1 源码确认 encrypted=2/partial=4 与 codec config=2/EOS=4 冲突，需要保留 sync 并拒绝无法安全直通的加密/分片样本。

## 实施与验证

### 实际改变

- `compressJpeg` / 字节数组 `probeSize` 新增默认完整数组的 `byteCount`。负值或超长返回 null；0 仍交给原解码器。JPEG 编码次数、档位、ARGB_8888、原尺寸保持，临时缓冲按 byteCount 分配。
- MPF 解码和重组中原有的两份主图副本都已删除；改为主图限定范围解码与原 JPEG 头部元数据搬运。输出后缀、MPEntry 重算和全部读回校验保持。
- 实况分流复用原数组，GainMap 直接从原范围写入；新视频和厂商尾部分别追加。MotionPhoto 总长包含尾部，VideoLength 只为新视频长度。保留原控制点和完整性检查，并在转 Int 前验证范围、在最终流分配前验证总长。
- 编码器枚举和硬件优先排序均只在 lazy 初始化时进行；Surface/原分辨率/Main10/码率/回退保持。
- 两条音频复制路径统一调用 `audioBufferFlags`，保留普通样本字节/时间戳与 sync 标记，明确拒绝加密或分片样本，不能误用 codec config/EOS 标记。拒绝后沿用普通视频跳过或实况原视频保留路径。

### 开销证据

对主图长度 P 的 MPF，减少两个长度 P 的中间数组。对实况，减少主图、增益图以及“新视频+尾部”合并数组；普通 JPEG 被重新分流时还少一次完整源文件读取。数据仍需最终组装、元数据重算及安全校验，不能由数组数量推算端到端加速比例。

### 检查结果

- 使用已有项目隔离 Gradle home `.gradle/local-build-home`：`:app:assembleDebug` 通过（9 秒）；默认全局 Gradle init 与仓库策略冲突，未改动全局配置。
- 同一隔离环境 `:app:lintDebug` 通过（12 秒）：0 错误、32 警告，两处既有 `WrongConstant` 已消除。
- `git diff --check` 通过。独立源码复核结论待补充。
- 未新增或运行测试套件。当前没有 adb 连接设备或配置的 AVD，本轮未做媒体压缩/还原、设备耗时、相册兼容性或 HDR 观感验收。已有非零 GainMap/motion padding 布局没有扩展重构，继续依赖现有容器校验，不将本轮推广为新增格式支持。
- 同工作区另一项 HEIC 兼容任务的源码、版本、规范及需求段落保持，排除在本次提交范围外；本轮也不操作个人媒体或签名资料。
