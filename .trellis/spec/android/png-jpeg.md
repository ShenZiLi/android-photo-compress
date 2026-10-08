# PNG 开关与 JPEG 转换

## 范围与设置

- `SettingsEntity.compressPng: Boolean = false`；Room v5→v6 只新增 `compressPng INTEGER NOT NULL DEFAULT 0`，不清空设置、账本或缓存。
- 设置页显示“压缩PNG”开关；普通照片档位说明固定“JPEG / HEIF / PNG”。关闭时 PNG 显示“不支持”、不可勾选；开启后普通 PNG 与图片一样可勾选，不显示“不支持”，沿用 JPEG 92/85/76，不另设质量档。
- `UiState.todoItems()` 每次按当前开关重建 PNG 候选状态，不能沿用扫描缓存中的转换预检 `skipReason`；一级图集、二级网格、类型筛选、全选和底部统计共用该状态。切换后立即生效并清空未压缩选择，无须重扫。待恢复事务优先保持禁用；动画、透明度等安全检查继续在引擎写入原片前执行，不能把可勾选解释为一定能转换。
- 扫描完成仅更新 lastScanSec/cacheLogicVersion，初始化用 INSERT IGNORE；不得拿扫描开始时的旧设置快照覆盖用户刚保存的 PNG 开关和档位。
- 开关不影响已转换 JPEG 的分类和还原。当前判类逻辑版本为 4（HEIC 新增，PNG 开关规则不变）；PNG 设置在 UI 候选和引擎入口分别检查，禁止只让按钮可点。

## 编码与信息

`PngCompressor.probe(file)` 只读图像头/块边界；`compress(bytes, tier, marker, checkCancelled)` 检查完整 PNG CRC、解压长度及像素，输出 JPEG。

当前安全范围：32 MiB 内、1600 万像素内、非像素扩展块合计不超过 4 MiB 的 8 位或 16 位非交错静态 PNG。位深闸门只放行 8 与 16：16 位允许色彩类型 0/2/4/6，由平台解码降为 8 位每通道后编码，属不可逆精度损失，必须写入 `codecUsed`（`PNG16 → JPEG q=N`）；设置页「压缩PNG」说明统一为「压缩后转为JPEG」，位深细节不在 UI 展开；1/2/4 位是位打包存储，给出独立原因跳过；16 位配调色板属非法组合，直接拒绝。像素上限与解码路径不分位深，16 位与 8 位共用同一张 ARGB_8888 位图，峰值内存一致。

透明像素、APNG、未知危险块、HDR 信号或无法保持解码色彩空间时跳过原片；不自动铺底色。尺寸不变，EXIF TIFF 搬入 APP1，XMP 合并唯一标记，其他原始非 IDAT 数据块含 CRC 按顺序归档到带序号的 `QingcunPNG` APP15。JPEG 没有对应标准字段的 PNG 数据以专有归档保留，不承诺普通相册理解这些字段。

位深判定集中在 `PngCompressor.depthReason`，`probe` 与 `parse` 必须调用同一函数，禁止扫描期与压缩期给出不同结论。跳过文案不得再声称只支持 8 位；`Image.stride` 与滤波左邻距离按「每像素字节数」换算，16 位为 2 字节/样本。

写入前验证 JPEG 解码尺寸/色彩空间、XMP 标记、EXIF 与归档字节。16 位源经平台解码得到扩展 sRGB（`scRGB-nl`），转 8 位 JPEG 后为标准 sRGB，二者不相等属已声明的降级路径，不算保真失败；8 位源仍要求色彩空间往返完全一致。不产生更小结果时按既有 SKIPPED 无收益规则处理，PNG 不改名、不写入。

## 同一媒体转换事务

`PngConversionRewriter.prepare(...)` 先保存原始备份及 `RecoveryJournal.Entry`：原路径、目标 JPEG 路径、inode、原始 SHA、精确 mtime、真实媒体日期（含 null）和原 URI。`writeJpeg` 原地写同一文件，再由 `MediaStoreUpdater.renameExisting` 修改 DISPLAY_NAME/MIME_TYPE，保持原目录、媒体 ID 和 inode。

后缀 .png→.jpg 是用户明确批准的格式转换例外，不扩大到 HEIC 或其他格式；禁止删除原 URI、插入新的媒体条目或覆盖同名 JPEG。目标已存在直接跳过。媒体改名、日期、摘要、登记或取消任一环节失败，独立撤销登记，再将同一文件原地恢复 PNG、改回原名并核对时间与媒体身份。完整通过后才清理本次备份；未完整通过继续保护并在未压缩页提供恢复入口。

恢复记录新增 `convertedPath/sourceInode`，缺省 null 兼容旧记录。待恢复匹配同时覆盖原/转换路径和媒体 URI，防止把未完成 JPEG 的 XMP 误接管成已压缩项；清理保护两条路径及原始/安全备份。

## 还原与验证矩阵

- `restorePngJpeg` 先验证原 PNG 备份，保留当前 JPEG 安全副本，再还原同一 inode 的内容及 PNG 文件名。旧 HEIC 转换还原不套用此路径。
- `recoverPngEntry` 在启动或详情中根据原 URI/inode 找到当前文件，恢复原 PNG；路径占用、媒体身份变化、坏备份均不覆盖其他文件。
- 完整恢复包含原始字节、PNG 名称、媒体 ID/日期及纳秒 mtime；文件创建时间仍遵循设备实测限制，不以 inode 或相册排序冒充创建时间验收。
- 开关关闭→无写入；透明/APNG/同名 JPEG→原片保留；正常→JPEG/DONE 有 PNG 备份；无收益→PNG/SKIPPED 无备份；写入/登记/取消失败→回滚 PNG/TODO；中断→持久入口，完整恢复前禁止重新压缩或清理。

原生模拟器验证证据见 [PNG 转换记录](../../tasks/10-04-photo-compress-app/research/png-jpeg.md)。不将未执行的真机或特殊格式验收标为通过。
