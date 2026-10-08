# PNG 开关与 JPEG 转换

## 范围与设置

- `SettingsEntity.compressPng: Boolean = false`；Room v5→v6 只新增 `compressPng INTEGER NOT NULL DEFAULT 0`，不清空设置、账本或缓存。
- 设置页显示“压缩PNG”开关；普通照片档位说明固定“JPEG / HEIF / PNG”。关闭时 PNG 显示“不支持”、不可勾选；开启后普通 PNG 与图片一样可勾选，不显示“不支持”，沿用 JPEG 92/85/76，不另设质量档。
- `UiState.todoItems()` 每次按当前开关重建 PNG 候选状态，不能沿用扫描缓存中的转换预检 `skipReason`；一级图集、二级网格、类型筛选、全选和底部统计共用该状态。切换后立即生效并清空未压缩选择，无须重扫。待恢复事务优先保持禁用；动画、HDR 等安全检查继续在引擎写入原片前执行，不能把可勾选解释为一定能转换。
- 扫描完成仅更新 lastScanSec/cacheLogicVersion，初始化用 INSERT IGNORE；不得拿扫描开始时的旧设置快照覆盖用户刚保存的 PNG 开关和档位。
- 开关不影响已转换 JPEG 的分类和还原。当前判类逻辑版本为 4（HEIC 新增，PNG 开关规则不变）；PNG 设置在 UI 候选和引擎入口分别检查，禁止只让按钮可点。

## 编码与信息

`PngCompressor.probe(file)` 只读图像头/块边界；`compress(bytes, tier, marker, checkCancelled)` 检查完整 PNG CRC、解压长度及像素，输出 JPEG。

当前安全范围：32 MiB 内、宽高符合 JPEG 的 1..65535、非像素扩展块合计不超过 4 MiB 的 8 位或 16 位非交错静态 PNG。不再设置 1600 万像素压缩上限；1600 万像素仅为串行调度门槛，实际能否处理取决于可用堆。位深闸门只放行 8 与 16：16 位允许色彩类型 0/2/4/6，转为 8 位每通道 JPEG 属不可逆精度损失，必须写入 `codecUsed`（`PNG16 → JPEG q=N`）；设置页「压缩PNG」说明统一为「压缩后转为JPEG」，位深细节不在 UI 展开；1/2/4 位是位打包存储，给出独立原因跳过；16 位配调色板属非法组合，直接拒绝。位图要求可变，允许 ARGB_8888 与 RGBA_F16；平台解码 16 位 PNG 可能忽略首选 ARGB_8888 而返回 RGBA_F16，不能以配置不符拒绝合法 16 位源。

透明像素由软件 `Canvas(bitmap)` 在原色彩空间逐行以白色 `Paint.blendMode = DST_OVER` 铺底；完全透明变白，不透明原样，不新增整张位图。每行检查取消，结束后 `canvas.setBitmap(null)` 解除原生引用，再释放源位图。不要用整数 getPixels/setPixels 混合广色域透明像素：这两者固定经 sRGB，可能先截掉原色域；见 [Bitmap API](https://developer.android.com/reference/android/graphics/Bitmap)。APNG、未知危险块、HDR 信号或无法保持解码色彩空间时仍跳过原片。尺寸不变，EXIF TIFF 搬入 APP1，XMP 合并唯一标记，其他原始非 IDAT 数据块含 CRC 按顺序归档到带序号的 `QingcunPNG` APP15。JPEG 没有对应标准字段的 PNG 数据以专有归档保留，不承诺普通相册理解这些字段。

位深判定集中在 `PngCompressor.depthReason`，`probe` 与 `parse` 必须调用同一函数，禁止扫描期与压缩期给出不同结论。跳过文案不得再声称只支持 8 位；`Image.stride` 与滤波左邻距离按「每像素字节数」换算，16 位为 2 字节/样本。

解码前检查可用堆，计入单张位图、源/输出数组拷贝、行缓冲、元数据副本及余量；8 位按每像素 4 字节，16 位按 RGBA_F16 的每像素 8 字节保守预算。编码完成先释放源位图，再验证 JPEG 解码尺寸/色彩空间、XMP 标记、EXIF 与归档字节，不能同时持有两张整图。16 位源经平台解码得到扩展 sRGB（`scRGB-nl`），转 8 位 JPEG 后为标准 sRGB，二者不相等属已声明的降级路径，不算保真失败；8 位源仍要求色彩空间往返完全一致。不产生更小结果时按既有 SKIPPED 无收益规则处理，PNG 不改名、不写入。

## 同一媒体转换事务

`PngConversionRewriter.prepare(...)` 先保存原始备份及 `RecoveryJournal.Entry`：原路径、目标 JPEG 路径、inode、原始 SHA、精确 mtime、真实媒体日期（含 null）和原 URI。`writeJpeg` 原地写同一文件，再由 `MediaStoreUpdater.renameExisting` 修改 DISPLAY_NAME/MIME_TYPE，保持原目录、媒体 ID 和 inode。

后缀 .png→.jpg 及同目录 JPEG 重名时追加数字是用户明确批准的格式转换例外，不扩大到 HEIC 或其他格式；禁止删除原 URI、插入新的媒体条目或覆盖同名 JPEG。目标选择在媒体锁内、备份之前执行：`照片.jpg` 已占用则依次尝试 `照片_1.jpg`、`照片_2.jpg`；每次为数字后缀/扩展名预留 255 字节 UTF-8 名称预算，必要时按完整 Unicode 码点缩短基名，现有符号链接也视为占用。恢复日志、相册刷新和账本必须共用实际选定路径。prepare 与改名仍检查目标不存在；外部在选名后占用路径时沿用拒绝和回滚保护。媒体改名、日期、摘要、登记或取消任一环节失败，独立撤销登记，再将同一文件原地恢复 PNG、改回原名并核对时间与媒体身份。完整通过后才清理本次备份；未完整通过继续保护并在未压缩页提供恢复入口。

恢复记录新增 `convertedPath/sourceInode`，缺省 null 兼容旧记录。待恢复匹配同时覆盖原/转换路径和媒体 URI，防止把未完成 JPEG 的 XMP 误接管成已压缩项；清理保护两条路径及原始/安全备份。

## 还原与验证矩阵

- `restorePngJpeg` 先验证原 PNG 备份，保留当前 JPEG 安全副本，再还原同一 inode 的内容及 PNG 文件名。旧 HEIC 转换还原不套用此路径。
- `recoverPngEntry` 在启动或详情中根据原 URI/inode 找到当前文件，恢复原 PNG；路径占用、媒体身份变化、坏备份均不覆盖其他文件。
- 完整恢复包含原始字节、PNG 名称、媒体 ID/日期及纳秒 mtime；文件创建时间仍遵循设备实测限制，不以 inode 或相册排序冒充创建时间验收。
- 开关关闭→无写入；APNG/内存不足/不支持的容器→原片保留；透明→白底 JPEG；同名 JPEG→数字后缀唯一名称；正常→JPEG/DONE 有 PNG 备份；无收益→PNG/SKIPPED 无备份；写入/登记/取消失败→回滚 PNG/TODO；中断→持久入口，完整恢复前禁止重新压缩或清理。

## 大图调度与回归

- `PngCompressor.MAX_PARALLEL_PIXELS = 16_000_000L` 为 AppViewModel 的调度常量。批次包含超过门槛或宽高未知 PNG 时，无论加速开关如何，图片与视频都经同一单路执行，避免重叠像素缓冲；其余批次沿用原有策略。
- 正常：大于 1600 万像素、内存可容纳的 PNG 原尺寸压缩；透明红色 alpha=128 转为约 RGB(255,127,127)，完全透明为白色；重名文件逐个跳过且摘要不变。
- 边界：内存预算不足、超出 JPEG 合法尺寸、坏 CRC/像素长度、APNG/HDR，均在原片写入前拒绝；取消沿用原有控制点。
- 验证采用 Debug 构建、Lint、差异检查及明确的独占合成样例，不新增或运行测试套件。记录尺寸、体积、白底像素、原样例摘要和目标选择；未实际执行的事务/原厂相册案例不得标为通过。
- 错误：移除像素检查后继续同时解码源 PNG 和完整输出 JPEG，或重名后覆盖现有路径。正确：解码前预算、逐行白底、源位图先释放；媒体锁内选唯一名称且 prepare/改名继续保护占用路径。

原生模拟器验证证据见 [PNG 转换记录](../../tasks/10-04-photo-compress-app/research/png-jpeg.md)。不将未执行的真机或特殊格式验收标为通过。
