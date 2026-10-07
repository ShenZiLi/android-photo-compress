# 多图 MPF 照片压缩

## 1. 范围

适用于可安全解析的 MPF JPEG，无两图、三图、四图或五图的固定张数上限，实际数量受 MPF payload 和条目表合法长度约束。外层主图按图片段质量档位重编码，全部附加图不重编码。三图及以上实况也走此路径，完整保留 Original、视频及尾部；一/双图实况继续使用已有视频转码路径。重叠、越界或无法安全解析的结构保留原片。

格式依据：[Android Ultra HDR Image Format](https://developer.android.com/media/platform/hdr-image-format)。实际样本还可能包含标准容器目录以外的厂商尾部，必须按原始字节保留。

## 2. 接口

- `JpegSegments.imageEnd(bytes, start, limit): Int?`：返回真实 JPEG EOI 后的绝对位置；失败返回 null。
- `MpfRewriter.entries(payload): List<Entry>?`：解析无符号属性、size、offset、依赖索引；验证 TIFF 与条目表。
- `MpfPhotoContainer.plan(bytes): Plan?`：记录真实主图结束位置、`auxiliaries: List<Auxiliary(start, size)>`、`imageCount` 和原始 MPF payload；列表顺序与原索引一致。
- `MpfPhotoContainer.rebuild(original, plan, encodedPrimary, xmp): ByteArray?`：搬运原元数据和整个主图后缀，重建并读回校验。

## 3. 不变条件

1. 唯一 MPF、最多一个标准 XMP 包；首图 offset 必须为 0；辅助图起点为 MPF payload 绝对位置 + 4 + MPEntry offset。
2. 主图 size 是可能陈旧的声明值；用 JPEG 段长度、熵流转义、重启标记及多扫描结构寻找实际 EOI，不能搜索首个 `FFD9`。
3. 每个辅助图声明 size 可能包含填充或内层 MPF/GainMap；其首个真实 JPEG 必须完整落在声明范围内，不缩短声明长度，不递归改写内层索引。按物理位置排序仅检查声明区间不重叠，重建条目仍使用原索引顺序。
4. 真实主图 EOI 后的间隔、辅助图、填充及未知尾部整体逐字节保留。不得从 EOF 减去 XMP 长度来定位辅助图。
5. EXIF、ICC、ISO HDR、厂商 APP、COM 原样搬运。MPF 只更改 size/offset；XMP 只增添自有唯一标记，并在原本存在正值主图长度时同步更新该长度。
6. 主图尺寸不变，输出大小须小于原图。全部辅助图新位置 = 原位置 + 新主图长度 − 原主图真实长度；每项声明 size 保持，所有 offset 重算到新 TIFF 基准。输出读回校验图像数量、主图结束位置和每项 size/offset。
7. 沿用安全事务、原始备份、日期校验和账本提交，不能直接覆盖源文件绕开 `CompressionEngine.commit`。

## 4. 验证与错误

- TIFF 端序、magic、IFD 边界、B001/B002 类型及计数、唯一标签、条目表完整性必须有效。
- size/offset 必须能表示为 uint32；JPEG 格式、每张辅助图边界及声明区间、0..imageCount 范围的依赖索引必须有效。
- 重组或尺寸校验失败、元数据不能完整保留、无压缩收益：明确跳过，原片不变。
- 写入或恢复失败遵循 [媒体错误恢复](media-recovery.md)，保留恢复记录与备份，停止后续项。

## 5. 样例

| 情况 | 处理 |
|---|---|
| 主图 + HDR GainMap，索引准确 | 压缩主图，保留增益图和 HDR 元数据 |
| 主图声明长度陈旧，但实际 JPEG 完整，辅助图偏移有效 | 用实际 EOI 重建主图长度 |
| 辅助图声明长度含 16 字节填充，文件末尾还有厂商数据 | 保留声明范围和完整后缀 |
| 三图已编辑实况：主图 + 增益图 + 内嵌 Original MPF，后接视频 | 压缩外层主图，Original、内层索引、视频和时间戳原样 |
| 四图、五图及更多 JPEG，索引物理顺序与条目顺序可不同 | 全部条目逐项校验并重建，不按固定数量写分支 |
| 多个外层 MPF 包、多个标准 XMP 包、声明区间重叠、越界或截断 | 跳过，保留原片 |

## 6. 验收依据

2026-10-07，0.1.23 API 36 原生 APP 操作，两份双图用户样本的副本：

- 平衡档（q85）分别减少 58.3%、53.0%。
- Android 原生解码器仍识别 HDR、Display P3、相同尺寸和相同增益图参数。
- 除必要 MPF 索引与自有标记外，元数据和完整后缀逐字节一致。
- 路径、inode、纳秒修改时间、MediaStore ID 与三个日期字段不变。
- APP 识别为已压缩并移出未压缩页；从备份还原后与原片逐字节一致。

详见 [双图 MPF 验收记录](../../tasks/10-04-photo-compress-app/research/mpf-dual-photo.md)。虚拟机不证明 ColorOS HDR 观感；inode/mtime 检查不替代文件创建时间验收。

0.1.25 进一步完成真实三图实况副本和四/五图合成文件的原生压缩与还原：三图整体减少 13.6%，四/五图分别减少 66.1% / 66.0%；全部附加图、内嵌 Original、视频与尾部逐字节保持，外层 MPF 所有索引重建正确；原生 HDR、色彩、文件身份与日期核对通过，还原文件逐字节一致。内嵌 HEVC/AAC 视频完整解码通过。详见 [多图 MPF 记录](../../tasks/10-04-photo-compress-app/research/mpf-multi-photo.md)。未把合成样本当作所有四/五图厂商结构验收。

## 7. 常见错误

错误：删除附加图再保存普通 JPEG；只修正第二项索引；对含 Original 的已编辑实况套用三段重组器；按陈旧主图 size 切片；尾部长度倒推增益图；offset 视为绝对地址；整体解码 HDR 后重编码基图再附旧增益图。

正确：真实 JPEG 边界 + MPF TIFF 相对偏移定位；仅提取主图解码；整段后缀原样保留；重建后读回索引，再走安全原地事务。
