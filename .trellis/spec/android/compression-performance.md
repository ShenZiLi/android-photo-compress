# 压缩性能契约

## 1. 范围与触发

适用于 JPEG/MPF/实况主图编码、实况容器重组与视频编码器选择。性能优化不得改变质量档位、原尺寸、HDR、元数据或可恢复事务。批处理保持串行，`Dispatchers.IO` 的作用是后台执行，不能据此声称多张照片并行。

## 2. 接口

- `JpegCompressor.compressJpeg(source, quality, byteCount = source.size)`：只解码原数组 `[0, byteCount)` 中的主图，按指定质量编码一次。
- `JpegCompressor.probeSize(bytes, byteCount = bytes.size)`：只读取该范围的图像尺寸，不解码完整像素。
- `CompressionEngine.compressLivePhoto(..., sourceBytes = null)`：普通照片被重新分流时复用已读源数组；直接入口自行读取。
- `VideoTranscoder.codecInfos`：进程级 lazy 枚举，并缓存硬件优先的顺序。
- 音频标记转换：Extractor 的 sync 映射为 Codec 的 key-frame；加密或分片样本不直接混用标记，明确拒绝本轮不支持的解密/合并路径。

## 3. 不变条件

- JPEG 档位仍为 92/85/76，`inSampleSize = 1`；每张主图只调用一次 JPEG 编码，不迭代试压。
- 主图的有效长度必须在原数组范围内；不能将 GainMap/附加图/内嵌视频交给主图解码器。元数据仍从原 JPEG 头部完整搬运。
- GainMap 从原数组的已验证区间直接写入最终输出。MPF 的整个附加图后缀继续由既有重组器逐字节保留。
- 内嵌视频编码成功时，新视频和原厂商尾部分别写入输出；`MotionPhoto` 总长度包含尾部，`OpCamera:VideoLength` 只包含新主视频。失败、无收益或不支持的封装仍保留原视频段。
- 视频继续优先选择满足原分辨率的硬件编码器；Surface 传帧、音频样本直通。排序缓存不改变 Main10、尺寸、码率或回退门槛。
- 普通音频的样本字节和时间戳保持；不能将 `SAMPLE_FLAG_ENCRYPTED`/`SAMPLE_FLAG_PARTIAL_FRAME` 原值写入 `BufferInfo`，两者分别与 codec config/EOS 冲突。
- 所有写前校验、取消控制点、备份、Mutex、媒体刷新及失败恢复保持；不得为提速删除安全检查。

## 4. 校验与错误

| 条件 | 行为 |
| --- | --- |
| 主图范围非法、源或容器不能解码 | 编码/探测拒绝，原文件不改写 |
| 有效 MPF/实况主图，包含辅助图或厂商尾部 | 只编码主图；后缀及元数据保持 |
| 内嵌视频转码失败或无收益 | 保留原视频，允许主图独立收益 |
| HDR 无合适 Main10 编码器或产物校验不通过 | 跳过，禁止静默降级 |
| 加密或分片音频样本 | 转码明确拒绝，普通视频保留原片；实况沿用原视频段保留分支 |
| 改写/同步/登记失败或取消 | 沿用安全回滚，不以性能优化绕过事务 |

## 5. 正常与边界案例

- 普通 JPEG：默认完整数组解码，单次编码和质量档位保持。
- 含大视频段的实况：原数组中的主图限定解码，增益图不另建整段副本；新视频与尾部分别写入最终流。
- 多图 MPF：主图限定范围，附加图、内嵌 Original 和视频后缀保持，输出索引与尺寸照常校验。
- 越界主图、损坏 JPEG、错误 XMP/MPF：仍拒绝处理；不能为了少做检查而截断源数据。

## 6. 验证要求

完成调试构建、修改范围 Lint/差异检查和完整代码复核。按项目当前约定不新增或运行测试套件；设备验收只能使用明确的独占副本，检查主图尺寸、元数据、HDR、GainMap/尾部字节、实况播放、还原摘要与取消。

性能比较需同一设备、同一批次、同档位，分别记录普通 JPEG/MPF/实况/视频的总耗时。少一次读取或数组拷贝是可审查的开销变化，不能直接换算为端到端加速比例。

## 7. 错误与正确示例

错误：`compressJpeg(original.copyOfRange(0, primaryEnd), quality)`；或先 `newVideo + vendorTail` 再写入最终流。

正确：`compressJpeg(original, quality, byteCount = primaryEnd)`；最终流分别写入新视频和原尾部，并保留全部长度/索引校验。
