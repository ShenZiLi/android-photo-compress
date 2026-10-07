# HEIC 原格式压缩实现与验收（2026-10-07）

## 方案与边界

用户要求实现 HEIC 压缩，保持既有路径、时间、元数据及可恢复要求。停用的 HEIC→JPEG 路径不再启用；新增 `core.heif` 使用 AndroidX HeifWriter 1.1.0 编码临时 HEIC，再重组原容器。只替换主图 HEVC 编码 extent 及其配置关联，其余 item、EXIF、ICC/XMP、引用、厂商 QTI 框和尾部字节原样保留。

HEIF 网格、属性、iloc 文件/idat 构造方法和数据区间经过校验。相同网格布局才允许替换；未知、共享或外部 extent、HDR、高位深、深度/透明辅助项、序列、旋转裁切属性等在原片写入前保留并说明原因。输入限制 64 MiB / 2000 万像素。sRGB/Display P3 输入保留 RGB 数值及原色彩属性，读回检查尺寸、色彩空间和抽样像素；不是所有 ICC/HDR 的通用转换。

HEVC CQ 与 JPEG quality 不等价：首次用 JPEG 的 85 编码得到无收益结果，原片不写，按既有 SKIPPED 归类；独立配置 HEIC 75/55/35 对应普通照片三个档位。实际收益和观感须按内容判断。

写前备份 SHA 必须匹配编码所用原字节，写回核对临时结果 SHA；使用既有原地写入、媒体日期同步、持久恢复、错误/取消回滚及原备份还原流程。新增自有 UUID 内 XMP 标记，扫描可读取以避免清数据后重复压缩；不替换原有 XMP。缓存版本 4 触发 HEIC 重新判类。

官方参考：[AndroidX HeifWriter 发布记录](https://developer.android.com/jetpack/androidx/releases/heifwriter)、[HeifWriter.Builder](https://developer.android.com/reference/androidx/heifwriter/HeifWriter.Builder)、[libheif 容器能力与元数据](https://github.com/strukturag/libheif)。没有引入 libheif 原生依赖。现有 JPEG 转换探针改为 HEIC 原格式接口和只读私有样本，未增加测试方法或运行测试套件。

## API 36 原生验收

使用用户提供的 HEIC，在安卓虚拟机生成独占 `HEIC-QA-0131/sample.heic` 副本；不改桌面原文件，不在真机手动处理个人媒体。该样本主图 3072×4096、48 个 512×512 HEVC tile，包含原始 ICC、EXIF、QTI 和水印尾部。截图、原文件、产物、数据库及脚本只保存在忽略目录 `.tmp-device/heic-acceptance`、`local-device-reports`。

| 档位 | CQ | 原始字节 | 输出字节 | 减少 |
| --- | --- | --- | --- | --- |
| 高质量 | 75 | 3,436,531 | 2,990,534 | 13.0% |
| 平衡 | 55 | 3,436,531 | 1,391,715 | 59.5% |
| 更省空间 | 35 | 3,436,531 | 639,107 | 81.4% |

- 三档均为 HEIC，路径和 MIME/账本格式保持；媒体 ID、DATE_ADDED/DATE_MODIFIED/DATE_TAKEN、inode 和纳秒 mtime 与处理前一致。原生输出解码尺寸、色彩和抽样像素检查通过。
- 平衡结果独立解析，原始 EXIF/grid/ICC、iinf/pitm/iref/idat、QTI 与水印尾部逐字节相等，item ID 保持；只重编码主图 HEVC。每次重组内部也核对全部非主图载荷和非编码属性。
- 三档通过原生已压缩页还原，每次原始 HEIC SHA、路径、媒体身份/日期和 mtime 恢复；原始 5020 条账本保留。恢复后本次私有备份和入口清理。
- 对该独占副本设置 SQLite INSERT 失败触发器，在 HEIC 已写入后令真实登记失败。APP 完整回滚原 HEIC，源 SHA/stat/日期与原片相同，未留下本次 DONE、备份或恢复记录，继续位于未压缩页可选择。
- 独占副本登记延迟中通过原生任务栏“取消”，当前项完整回滚，源 SHA/stat/日期与原片相同，账本仍为 5020，未新增本次备份或恢复入口。
- 故障与延迟触发器已移除；临时图集过滤、PNG 开关和普通照片档位恢复至检查前。两份既有保护恢复记录未清理或冒充本次遗留。

## 限制

最终新增写回 SHA 核对后重新构建调试/正式 APK，并在 API36 重复平衡档压缩和原生还原；最终平衡输出仍为 1,391,715 B。正式签名、包名 `com.photocompress.app`、轻存标签及 0.1.31/code32 核验通过。未重新运行全量 Lint，既有 VideoTranscoder 两项 WrongConstant 不在本次范围。

上述数字仅代表该样本，不承诺所有 HEIC 都有固定收益。当前不是 10 位 HDR、深度/透明或任意厂商扩展的通用支持；未验证场景会在预检保留原片。逐字节保留和粗大像素偏差检查不替代真机相册、HDR 观感或肉眼无损验收。文件创建时间、其他厂商/固件及真正强杀写入中途的 HEIC 专项验收本轮未完成。
