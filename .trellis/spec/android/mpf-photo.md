# 静态双图 MPF 照片压缩

## 1. 范围

适用于不包含 MotionPhoto 视频的双图 MPF JPEG。主图按图片质量档位重编码，辅助图（HDR GainMap 等）不重编码。超过两图、重叠或越界结构保留原片。实况继续使用现有实况容器路径。

格式依据：[Android Ultra HDR Image Format](https://developer.android.com/media/platform/hdr-image-format)。实际样本还可能包含标准容器目录以外的厂商尾部，必须按原始字节保留。

## 2. 接口

- `JpegSegments.imageEnd(bytes, start, limit): Int?`：返回真实 JPEG EOI 后的绝对位置；失败返回 null。
- `MpfRewriter.entries(payload): List<Entry>?`：解析无符号属性、size、offset、依赖索引；验证 TIFF 与条目表。
- `MpfPhotoContainer.plan(bytes): Plan?`：记录真实主图结束位置、辅助图起点、声明长度和原始 MPF payload。
- `MpfPhotoContainer.rebuild(original, plan, encodedPrimary, xmp): ByteArray?`：搬运原元数据和整个主图后缀，重建并读回校验。

## 3. 不变条件

1. 唯一 MPF、最多一个标准 XMP 包；首图 offset 必须为 0；辅助图起点为 MPF payload 绝对位置 + 4 + MPEntry offset。
2. 主图 size 是可能陈旧的声明值；用 JPEG 段长度、熵流转义、重启标记及多扫描结构寻找实际 EOI，不能搜索首个 `FFD9`。
3. 辅助图声明 size 可能包含填充；真实 JPEG 必须完整落在声明范围内，不缩短原始声明长度。
4. 真实主图 EOI 后的间隔、辅助图、填充及未知尾部整体逐字节保留。不得从 EOF 减去 XMP 长度来定位辅助图。
5. EXIF、ICC、ISO HDR、厂商 APP、COM 原样搬运。MPF 只更改 size/offset；XMP 只增添自有唯一标记，并在原本存在正值主图长度时同步更新该长度。
6. 主图尺寸不变，输出大小须小于原图；MPF 主图长度和辅助图偏移必须匹配重组后的真实位置。
7. 沿用安全事务、原始备份、日期校验和账本提交，不能直接覆盖源文件绕开 `CompressionEngine.commit`。

## 4. 验证与错误

- TIFF 端序、magic、IFD 边界、B001/B002 类型及计数、唯一标签、条目表完整性必须有效。
- size/offset 必须能表示为 uint32；JPEG 格式、辅助图边界和依赖索引必须有效。
- 重组或尺寸校验失败、元数据不能完整保留、无压缩收益：明确跳过，原片不变。
- 写入或恢复失败遵循 [媒体错误恢复](media-recovery.md)，保留恢复记录与备份，停止后续项。

## 5. 样例

| 情况 | 处理 |
|---|---|
| 主图 + HDR GainMap，索引准确 | 压缩主图，保留增益图和 HDR 元数据 |
| 主图声明长度陈旧，但实际 JPEG 完整，辅助图偏移有效 | 用实际 EOI 重建主图长度 |
| 辅助图声明长度含 16 字节填充，文件末尾还有厂商数据 | 保留声明范围和完整后缀 |
| 多个 MPF 包、多个标准 XMP 包、超过两图、越界或截断 | 跳过，保留原片 |

## 6. 验收依据

2026-10-07，API 36 原生 APP 操作，两份用户样本的副本：

- 平衡档（q85）分别减少 58.3%、53.0%。
- Android 原生解码器仍识别 HDR、Display P3、相同尺寸和相同增益图参数。
- 除必要 MPF 索引与自有标记外，元数据和完整后缀逐字节一致。
- 路径、inode、纳秒修改时间、MediaStore ID 与三个日期字段不变。
- APP 识别为已压缩并移出未压缩页；从备份还原后与原片逐字节一致。

详见 [双图 MPF 验收记录](../../tasks/10-04-photo-compress-app/research/mpf-dual-photo.md)。虚拟机不证明 ColorOS HDR 观感；inode/mtime 检查不替代文件创建时间验收。

## 7. 常见错误

错误：删除第二图再保存普通 JPEG；按陈旧主图 size 切片；尾部长度倒推增益图；offset 视为绝对地址；整体解码 HDR 后重编码基图再附旧增益图。

正确：真实 JPEG 边界 + MPF TIFF 相对偏移定位；仅提取主图解码；整段后缀原样保留；重建后读回索引，再走安全原地事务。
