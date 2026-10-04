# ColorOS / OPPO 系统媒体格式调研

调研目的：确认当前压缩方案（D6：JPEG、HEIF/HEIC、实况照片、视频 HEVC/H.264/AV1/VP9、音频 AAC）是否覆盖全面。

调研方式：本机 `agent-reach` / `opencli` / `mcporter` / `conda` 均不存在，agent-reach 无可用后端，改用内置联网搜索取证（OPPO 官网参数页、OPPO/ColorOS 支持站、realme 官网参数页、Android 开发者官方支持格式文档、第三方教程）。所有结论标注来源；未能确证的条目在文末单列。

调研日期：2026-10-04。

---

## 1. 目标设备事实

- realme GT7 Pro（realme 官网全球/台湾/欧洲参数页一致）出厂为 **realme UI 6.0，基于 Android 15**；用户称当前系统为 ColorOS 16（realme UI 与 ColorOS 同源）。两者相册/媒体框架同源代码，但**具体固件版本与相册行为必须以真机读取为准**。
- GT7 Pro 拍摄功能（台湾站中文原文）：拍照、錄影、街拍、夜景、人像、專業、全景、高畫素、電影、縮時攝影、慢動作、長曝光、多景錄影、文件、移軸、水下相機、**實況照片**、迅馳閃拍、Google 智慧鏡頭、美顏。
- GT7 Pro 录像规格：8K/24fps；4K 30/60fps；1080P 30/60fps；720P 30/60fps；1080P/240fps 与 720P/240fps、480fps 慢动作。

## 2. 相机可选输出格式

OPPO 官网机型参数页（Find 系列、Reno 系列）统一声明：

> 照片格式：**JPG（默认），HEIF，RAW**

相关能力：

- **HEIF**：ColorOS 11 起支持拍摄；支持 **10-bit（10 亿色）**；相机「拍摄格式」可切 JPEG/HEIF；关闭「10 亿色影像」与「高效图片存储」可强制回 JPEG。ColorOS 13.1+ 相机设置提供更灵活的格式选择。（OPPO 支持站、ColorOS 使用手册）
- **RAW**：专业/大师模式可输出 RAW；旗舰机型有 **RAW MAX**（单张可超 50MB）。
- **ProXDR / Ultra HDR**：OPPO 的高动态显示与增益图技术；我们手上的样张已实测带 **GainMap 增益图**。
- **人像模式**：支持**拍摄后重新调整景深/光圈（F1.4–F16）、虚化形状、光斑**——说明人像照片内必须携带**深度/景深信息**，重编码时若丢弃将导致后期虚化编辑失效。

## 3. 视频输出

- OPPO/realme 相机录制容器统一为 **MP4**；编码以 **H.265(HEVC)/H.264** 为主。
- **O-Log**（OPPO 官方白皮书）：**H.265 YUV 4:2:0、10-bit** Log，4K60 参考码率约 120Mbps。
- ColorOS 16 旗舰支持 **4K 120fps 杜比视界（Dolby Vision）**、8K 30fps 10-bit Log。
- **慢动作**（1080P/240、720P/480）与**延时摄影**（4K/1080P 30fps）输出也是 MP4，但带播放速率/时间戳语义。
- **屏幕录制**：ColorOS 内置录屏，输出 **MP4**，单文件上限 5GB / 30 分钟（ColorOS 官方说明）。

## 4. 相册里实际会出现的媒体类型

ColorOS 相册「图集」自带分类（ColorOS 官方使用手册原文）：**所有照片、相机、截屏录屏、收藏、视频、动图、最近删除**，另有人物/地点智能图集。

由此可推断相册中至少存在这些来源与类型：

| 来源 | 产出媒体 | 说明 |
|---|---|---|
| 相机拍摄 | JPEG / HEIF / DNG | 见 §2 |
| 实况照片 | JPEG + 内嵌 MP4（我们的样张已确证） | 见 §5 |
| **截屏（普通/区域/长截屏）** | **PNG**，存于相册 `Screenshots` 文件夹 | 长截屏可达 1080×100000 |
| 录屏 | MP4 | 见 §3 |
| 全景 / 长曝光 / 高像素 / 移轴 / 水下 | JPEG 或 HEIF | |
| 慢动作 / 延时 / 电影 / 多景（双景） | MP4 | |
| 下载、微信/QQ 保存、蓝牙接收 | **WebP、GIF、BMP、AVIF、JPEG、PNG** 等 | ColorOS 相册带独立「动图」图集，说明 GIF/动态图是一等公民 |
| iPhone / 其他品牌导入 | HEIC、HEIC 动态照片、MOV、H.264/HEVC MP4 | Android 平台支持 HEIC 动态照片 |
| 第三方编辑/导出 | 上述任意组合 | |

厂商侧对图片格式的浏览支持（OnePlus 9 的 ColorOS 机型参数，作为同源系统参考）：
浏览 **JPEG、PNG、BMP、GIF、WEBP、HEIF、HEIC、DNG**；输出 JPEG、DNG。

Android 平台层面（Google 官方「支持的媒体格式」与 Media3/ExoPlayer 文档）：

- 图片：**JPEG Ultra HDR、PNG、WebP、HEIF/HEIC、HEIC 动态照片、AVIF（Android 14+ 起编解码强制）**。
- 容器：图片 WebP/HEIF/AVIF；视频 MP4、Matroska(MKV)、WebM、3GPP、TS 等（更多为解码支持）。

## 5. 实况照片格式（结合实测样张）

实测样张 `C:\Users\Admin\Desktop\实况图片.jpg`（12,478,846 字节）：

- XMP 命名空间：`GCamera`（Google）、**`OpCamera`（`http://ns.oplus.com/photos/1.0/camera/`）**、`Container`、`Item`、`hdrgm`。
- 字段：`GCamera:MotionPhoto="1"`、`GCamera:MotionPhotoVersion="1"`、`GCamera:MotionPhotoPresentationTimestampUs="1220005"`、`OpCamera:MotionPhotoOwner="oplus"`、`OpCamera:OLivePhotoVersion="2"`、`OpCamera:VideoLength="5116097"`。
- `Container:Directory` 三项：`Primary`(image/jpeg, Length=0)、`GainMap`(image/jpeg, 430057)、`MotionPhoto`(video/mp4, 8331026)。
- 内嵌视频：HEVC（`hvc1`/`hvcC`）+ AAC（`mp4a`）。

结论：ColorOS/oplus 实况照片 = **Google Motion Photo v2 容器 + oplus 私有扩展 + HDR 增益图**，静态图为 JPEG。

同时需注意存在其他实况/动态照片变体：

- **Google Motion Photo v1**（旧 `GCamera:MicroVideo` / `MicroVideoOffset`）——旧机型或跨品牌导入。
- **HEIC 动态照片**（HEIF 容器内的动态照片，ExoPlayer 明确支持）——iPhone 导入。
- iPhone 导入的 Live Photo 常为 **HEIC/JPG + MOV 成对文件**，而非单文件内嵌。
- OPPO 大师模式的 **Master Motion Photo**（同为实况类）。

## 6. 覆盖对照（对照 D6 现状）

| 类别 | 实际存在的形态 | 当前 D6 覆盖 | 判定 |
|---|---|---|---|
| 相机静态照片 | JPEG、HEIF/HEIC | JPEG、HEIF/HEIC | ✅ 覆盖（HEIC 元信息保留仍有 U3 风险） |
| 相机 RAW | DNG / RAW MAX | 未提及 | ❌ **缺口**（应识别并默认跳过） |
| HDR 静态照片 | Ultra HDR / ProXDR（内嵌 GainMap） | 仅实况照片提到 GainMap | ⚠️ **风险缺口**（普通照片也会带 GainMap） |
| 人像模式照片 | JPEG + 深度/景深信息 | 未提及深度数据保留 | ⚠️ **风险缺口**（丢失后无法后期调虚化） |
| 实况照片 | JPEG+MP4（oplus v2）；Google v1；HEIC 动态照片；HEIC+MOV | 仅 JPEG 主图+GainMap+MP4 | ⚠️ **部分覆盖**（变体未覆盖） |
| 截图 | **PNG**（含超长图） | 未提及 PNG | ❌ **缺口** |
| 动图 | **GIF / 动态 WebP** | 未提及 | ❌ **缺口** |
| 下载/第三方图片 | **WebP、BMP、AVIF** | 未提及 | ❌ **缺口** |
| 相机视频 | MP4（HEVC/H.264） | HEVC/H.264/AV1/VP9 | ✅ 编码覆盖；容器仅隐含 MP4 |
| HDR 视频 | 10-bit HEVC、HLG/PQ、**杜比视界**、O-Log 10-bit Log | 未提 10-bit/HDR 元数据保留 | ⚠️ **风险缺口**（8-bit 重编码会毁 HDR） |
| 慢动作 / 延时 | MP4 + 播放速率语义 | 未提速率元数据保留 | ⚠️ **风险缺口** |
| 录屏 | MP4 | 隐含覆盖 | ✅ |
| 其他容器 | MKV、MOV、WebM、3GP、TS（导入/下载） | 未提 | ❌ **缺口** |
| 音频 | AAC 为主，导入可能 MP3/Opus/FLAC | AAC | ⚠️ 部分覆盖 |

## 7. 结论

**当前覆盖不全面。** 用"普通照片 / 实况照片 / 视频"三分法在**概念上成立**，但按**实际文件格式与内部结构**看，至少存在 6 处明确缺口与 4 处风险缺口：

明确缺口（D6 未涉及）：
1. 截图与长截屏的 **PNG**（相册有独立「截屏录屏」图集，量通常很大，长截屏单体可达数十 MB）
2. **GIF / 动态 WebP**（相册有独立「动图」图集）
3. **WebP / BMP** 静态图
4. **AVIF** 图片（Android 14+ 强制支持，只把 AV1 算进视频是不够的）
5. **RAW / DNG**（需识别并默认跳过，避免破坏后期空间）
6. 视频**容器**维度（MKV/MOV/WebM/3GP/TS），不只是编码维度

风险缺口（内部结构/元数据）：
7. 普通照片（非实况）也可能带 **GainMap/Ultra HDR**
8. **人像模式的深度/景深信息**必须原样保留
9. 视频需保留 **10-bit 位深 + HDR 元数据（HLG/PQ/杜比视界）与 Log 曲线**
10. 慢动作/延时需保留**播放速率**语义

另有两点与产品设计相关：

- ColorOS 相册自带「最近删除」保留 **30 天**——与我们计划的回收站 30 天叠加，需在空间统计口径上明确区分。
- HEIC 的元信息保留（原 U3）仍是 HEIC 能否纳入的前提。

## 8. 未能确证、需真机确认的条目

- 真机实际系统版本与相册行为（realme UI 6.0/Android 15 vs 用户所述 ColorOS 16）。
- ColorOS 16 相机设置里 HEIF、RAW、10-bit 的**实际可选项与默认值**。
- 截图/长截屏的实际文件格式与目录（第三方资料称 PNG 存 `Screenshots`，未见官方明确说明）。
- 人像模式深度信息的**实际承载方式**（JPEG 内 MPF 深度图 / 独立深度文件 / 私有扩展）。
- 普通照片是否普遍携带 GainMap。
- 实况照片是否仅 oplus v2，还是同时存在 v1 / HEIC 动态照片变体。
- 设备是否有 AV1/VP9 硬件编码器（原 U4）。
- 「动图」图集中 GIF 与动态 WebP 的比例与实际体积占比。

上述项应并入实施计划阶段 1 的「真机能力探针」，结论落盘到 `research/device-capability-report.md`。

## 9. 来源

- OPPO 官网机型参数与机型对比页（照片格式 JPG/HEIF/RAW、拍摄模式清单）
- OPPO 支持站《如何修复 OPPO 手机上的 HEIF 影像相容性问题》（HEIF 10-bit、ColorOS 13.1 拍摄格式设置）
- ColorOS 官方使用手册：相机设置（HEIF）、相册（图集分类含「截屏录屏」「动图」「最近删除 30 天」）、屏幕录制（MP4，5GB/30 分钟）、截屏
- realme 官网 realme GT 7 Pro 参数页（realme UI 6.0 / Android 15、拍摄与录像功能与规格）
- OPPO 官方《OPPO O-Log White Paper》（H.265 4:2:0 10-bit、码率表）
- OPPO 官网 Find X9 / Find X9 Pro / Find X10 产品页（4K 120fps 杜比视界、8K 30fps 10-bit Log、RAW MAX、ProXDR）
- Android 开发者《支持的媒体格式》《Media3/ExoPlayer 支持的格式》（AVIF Android 14+、WebP、HEIF/HEIC、HEIC 动态照片、JPEG Ultra HDR、容器清单）
- OnePlus 9 机型参数页（ColorOS 同源系统的图片浏览/输出格式清单）
- 第三方教程（长截屏生成 PNG 存 `Screenshots`；人像模式后期可调景深）
