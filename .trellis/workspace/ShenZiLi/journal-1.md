# Journal - ShenZiLi (Part 1)

> AI development session journal
> Started: 2026-10-04

---



## Session 1: 实况照片原厂相册播放修复
<!-- trellis-session: v=2 fp=c60feacdb8b390f0 -->

**Date**: 2026-10-05
**Task**: 实况照片原厂相册播放修复
**Branch**: `master`

### Summary

以用户确认可播放的 PC-OEM 副本为对照，定位原地压缩后媒体库实况段长度缓存陈旧，修复单项扫描与读回校验；0.1.1 已部署，用户确认真机修复成功。

### Main Changes

- 同一 URI 显式 IS_PENDING=0，核对实际大小、实况段长度、身份和日期，刷新失败不记成功并校验恢复结果。
- 新增媒体库同步规范与根因验收报告；个人媒体及原始诊断均保持在忽略目录。

### Git Commits

| Hash | Message |
|------|---------|
| `69a551e` | fix(livephoto): refresh OEM motion metadata after in-place compression |

### Testing

- [OK] 67 JVM 回归通过；3 项短设备回归通过；前台完整压缩/还原设备回归通过；PC-OEM 原厂播放和修复结果由用户确认。

### Status

[OK] **Completed**

### Next Steps

- 真机后续验证由用户自行进行；项目其他格式及全部文件系统时间的整体验收继续独立跟踪。


## Session 2: 首页统计卡精简与单行数值布局
<!-- trellis-session: v=2 fp=25af72daa8262aa0 -->

**Date**: 2026-10-05
**Task**: 首页统计卡精简与单行数值布局
**Branch**: `master`

### Summary

删除已压缩卡箭头和结果体积，节省量自适应单行显示，类型文案缩短为图片与实况，生成 0.1.2 APK。

### Main Changes

- 首页数值单行自适应并适度增加压缩率卡片宽度；首页与确认框类型名称保持一致。

### Git Commits

| Hash | Message |
|------|---------|
| `a8516cc` | fix(ui): simplify home statistics and keep savings on one line |

### Testing

- [OK] 仅编译 assembleDebug（15 秒）与差异检查通过；未新增或运行测试，真机布局由用户自行验收。

### Status

[OK] **Completed**


## Session 3: 0.1.2 首页调整版安装到真机
<!-- trellis-session: v=2 fp=48adb3d87657cbf9 -->

**Date**: 2026-10-05
**Task**: 0.1.2 首页调整版安装到真机
**Branch**: `master`

### Summary

0.1.2 APK 已覆盖安装到连接的 GT7 Pro，安装返回 Success，读回 versionName=0.1.2 / versionCode=3。

### Main Changes

- 覆盖安装已有应用，保留现有应用数据。

### Git Commits

| Hash | Message |
|------|---------|
| `a8516cc` | fix(ui): simplify home statistics and keep savings on one line |

### Testing

- [OK] 核对安装结果及实际安装版本；没有进行界面或媒体测试，真机验收由用户自行操作。

### Status

[OK] **Completed**


## Session 4: 删除预计节约量的档位说明
<!-- trellis-session: v=2 fp=9a320ad52e0e646f -->

**Date**: 2026-10-05
**Task**: 删除预计节约量的档位说明
**Branch**: `master`

### Summary

删除截图红框中的按平衡档估算字样，保留预计节约数值；0.1.3 已覆盖安装真机。

### Main Changes

- 仅调整未压缩页操作栏提示文案及版本号。

### Git Commits

| Hash | Message |
|------|---------|
| `18e725b` | fix(ui): remove quality tier note from savings estimate |

### Testing

- [OK] assembleDebug 编译通过（14 秒）、差异检查通过；安装返回 Success，读回 versionName=0.1.3 / versionCode=4；未运行测试或进行真机界面验收。

### Status

[OK] **Completed**


## Session 5: MPF 不支持提示去除图片名称
<!-- trellis-session: v=2 fp=2bf1fc5c05ca3940 -->

**Date**: 2026-10-05
**Task**: MPF 不支持提示去除图片名称
**Branch**: `master`

### Summary

MPF 提示调整为含2图 MPF 多图结构，本版本不处理。跳过原因提示移除文件名，0.1.4 已覆盖安装真机。

### Main Changes

- MPF 图数动态保留，移除含与图数之间的空格；跳过通知只显示原因，压缩判定规则不变。

### Git Commits

| Hash | Message |
|------|---------|
| `26c2e62` | fix(ui): simplify MPF skip message and remove filename prefix |

### Testing

- [OK] assembleDebug 编译通过（4 秒）、差异检查通过；安装 Success，读回 0.1.4 / versionCode 5。没有运行测试或操作真实媒体，界面由用户验收。

### Status

[OK] **Completed**


## Session 6: 精简压缩与还原完成提示
<!-- trellis-session: v=2 fp=f2dbc8d79be1006a -->

**Date**: 2026-10-05
**Task**: 精简压缩与还原完成提示
**Branch**: `master`

### Summary

提示采用压缩5项，节省58.8MB，跳过1项和还原1项，新增1.1MB格式；0.1.5 已覆盖安装真机。

### Main Changes

- 完成提示删除成功字样及间隔点，使用中文逗号，数值与单位不留空格；还原新增体积按原始体积与压缩后体积之差统计。

### Git Commits

| Hash | Message |
|------|---------|
| `9cc9217` | fix(ui): simplify batch completion messages and report restored size increase |

### Testing

- [OK] assembleDebug 编译通过（5 秒）、差异检查通过；安装返回 Success，读回 0.1.5 / versionCode 6；未新增或运行测试，没有操作用户媒体。

### Status

[OK] **Completed**


## Session 7: 首页双卡布局与占用文案调整
<!-- trellis-session: v=2 fp=37c67c4b5c61582e -->

**Date**: 2026-10-05
**Task**: 首页双卡布局与占用文案调整
**Branch**: `master`

### Summary

已节约改已省，移除共字和压缩率整卡，未压缩与已压缩改为两张等宽卡片，体积对比改占用对比；0.1.6 已安装真机。

### Main Changes

- 双卡内容内边距与下方对比图例对齐，统计口径沿用现有值。

### Git Commits

| Hash | Message |
|------|---------|
| `ad0616b` | fix(ui): simplify home summary to two equal statistics cards |

### Testing

- [OK] assembleDebug 编译通过（5 秒）、差异检查通过；安装 Success，读回 0.1.6 / versionCode 7；未运行测试，真机布局由用户自行验收。

### Status

[OK] **Completed**


## Session 8: 首页图例数值上下对齐与右对齐
<!-- trellis-session: v=2 fp=d92dcb6ff553b968 -->

**Date**: 2026-10-05
**Task**: 首页图例数值上下对齐与右对齐
**Branch**: `master`

### Summary

百分比、体积两列各共享动态测量宽度并右对齐，同一行文本基线对齐；0.1.7 已覆盖安装真机。

### Main Changes

- 按现有字体测量列宽并启用等宽数字，数值单行显示，保持占比统计口径。

### Git Commits

| Hash | Message |
|------|---------|
| `fe78f5e` | fix(ui): align home legend values in right-aligned columns |

### Testing

- [OK] assembleDebug 编译通过（5 秒）、差异检查通过；安装 Success，读回 0.1.7 / versionCode 8；未运行测试，真机界面由用户自行验收。

### Status

[OK] **Completed**


## Session 9: 删除设置页压缩比例入口说明
<!-- trellis-session: v=2 fp=d81fd7891c8b26a2 -->

**Date**: 2026-10-05
**Task**: 删除设置页压缩比例入口说明
**Branch**: `master`

### Summary

删除设置页压缩比例入口下的当前档位摘要，入口单行显示；0.1.8 已覆盖安装真机。

### Main Changes

- SettingRow 副标题改为可选并不渲染缺省内容，移除不再使用的 ratioSummary。

### Git Commits

| Hash | Message |
|------|---------|
| `fb7eb0b` | fix(ui): remove compression ratio subtitle from settings |

### Testing

- [OK] assembleDebug 编译通过（最终 3 秒）、差异检查通过；安装 Success，版本核对为 0.1.8 / versionCode 9；未运行测试，界面由用户验收。

### Status

[OK] **Completed**


## Session 10: 首页节省百分比固定负号与统计卡居中
<!-- trellis-session: v=2 fp=565068f6c4df5418 -->

**Date**: 2026-10-05
**Task**: 首页节省百分比固定负号与统计卡居中
**Branch**: `master`

### Summary

已省摘要百分比固定负号（含零值），未压缩和已压缩卡片内容改为居中，0.1.9 已覆盖安装真机。

### Main Changes

- 色点与标题组合、数量及体积在各自卡片居中，移除过时的对齐说明；统计口径不变。

### Git Commits

| Hash | Message |
|------|---------|
| `d90e81e` | fix(ui): center home statistics and prefix savings percentage with minus |

### Testing

- [OK] assembleDebug 编译通过（5 秒）、差异检查通过；安装 Success，读回 0.1.9 / versionCode 10；未运行测试，真机界面由用户自行验收。

### Status

[OK] **Completed**


## Session 11: 删除未压缩页未选择下方提示
<!-- trellis-session: v=2 fp=a91ff8bf3cdd68fe -->

**Date**: 2026-10-05
**Task**: 删除未压缩页未选择下方提示
**Branch**: `master`

### Summary

删除未压缩一级页面的勾选图集或图片后开始压缩提示，未选择时不渲染小字及空白行，与已压缩一级页面一致；0.1.10 已安装真机。

### Main Changes

- 移除未压缩一级未选择提示分支，沿用空字符串处理；更新说明注释。

### Git Commits

| Hash | Message |
|------|---------|
| `fdca0df` | fix(ui): remove unselected compression guidance from action bar |

### Testing

- [OK] assembleDebug 编译通过（5 秒）、差异检查通过；安装 Success，读回 0.1.10 / versionCode 11；未运行测试，真机界面由用户自行验收。

### Status

[OK] **Completed**


## Session 12: 轻存名称与桌面图标修复
<!-- trellis-session: v=2 fp=733beb90527f0cab -->

**Date**: 2026-10-06
**Task**: 轻存名称与桌面图标修复
**Branch**: `master`

### Summary

将实际安装工程的应用名称统一为轻存，接入另一工程中已选定的银白玉绿图标；0.1.11 覆盖安装真机，桌面截图确认名称和图标生效。

### Main Changes

- 替换 Android 16 实际选择的 v26 自适应图标，接入原稿、背景颜色和单色轮廓；首页及权限引导复用名称资源，记录跨工程错配根因。

### Git Commits

| Hash | Message |
|------|---------|
| `b33116d` | fix(branding): apply Qingcun name and launcher icon to installed app |

### Testing

- [OK] assembleDebug 7 秒通过；APK 标签、三层图标及原稿摘要核对通过；ADB 安装 Success，版本读回 0.1.11/code12，真机桌面确认图标和轻存名称；差异检查通过，未运行测试套件。

### Status

[OK] **Completed**


## Session 13: 轻存全局液态玻璃 UI
<!-- trellis-session: v=2 fp=d8522ebc89426773 -->

**Date**: 2026-10-06
**Task**: 轻存全局液态玻璃 UI
**Branch**: `master`

### Summary

按 ui-ux-pro-max 和 better-ui 统一全局玻璃材质、浮动导航、图集卡、设置与弹窗；保留现有操作与确认文案，0.1.12 已覆盖安装真机。

### Main Changes

- 增加 Haze 1.6.10 背景采样及共享 Glass 组件，深浅色语义令牌、节电/高对比回退、选择语义与触摸面积，修复透明容器文字继承和禁用渐变问题；持久化设计规范与审阅记录。

### Git Commits

| Hash | Message |
|------|---------|
| `1ab66d2` | feat(ui): apply liquid glass surfaces across Qingcun app |

### Testing

- [OK] 最终 assembleDebug 15 秒通过，差异检查通过；安装 Success，设备读回 0.1.12/code13，APK 轻存标签及包名正确；已查看深色首页和图集显示，其他页面、浅色、大字体、横屏及性能未完整实机验收；未运行测试套件或处理个人媒体。

### Status

[OK] **Completed**


## Session 14: 图片网格顶栏与筛选布局调整
<!-- trellis-session: v=2 fp=c2ecbd7ab082c689 -->

**Date**: 2026-10-06
**Task**: 图片网格顶栏与筛选布局调整
**Branch**: `master`

### Summary

同步修改未压缩、已压缩二级网格页：删除统计工具行，右上角全选，筛选胶囊紧凑居中，标题与返回按钮间距增加12dp；0.1.13 已安装真机。

### Main Changes

- 共享全选入口保留筛选范围、取消全选和禁用状态；32dp视觉胶囊仍有48dp触摸范围；AppBar 间距参数默认0，只调整两处网格页。

### Git Commits

| Hash | Message |
|------|---------|
| `4b0eb00` | fix(ui): compact media grid filters and move select all into header |

### Testing

- [OK] assembleDebug 8秒通过、差异检查通过；安装 Success，版本读回0.1.13/code14；未运行测试，页面效果由用户验收。

### Status

[OK] **Completed**


## Session 15: 删除已压缩页右上角删除图标
<!-- trellis-session: v=2 fp=01398893d496c81c -->

**Date**: 2026-10-06
**Task**: 删除已压缩页右上角删除图标
**Branch**: `master`

### Summary

移除已压缩一级图集页右上角回收站图标，清理无用回调和导入；0.1.14 已安装真机。

### Main Changes

- 删除 DoneLevel1 顶栏操作及其 onOpenTrash 参数，设置页回收站入口和二级网格全选沿用现有实现。

### Git Commits

| Hash | Message |
|------|---------|
| `7d46c1e` | fix(ui): remove trash shortcut from compressed albums header |

### Testing

- [OK] assembleDebug 6秒通过、差异检查通过；安装 Success，设备读回0.1.14/code15；未运行测试或操作真实媒体，界面由用户验收。

### Status

[OK] **Completed**


## Session 16: 统一一级二级页面全选按钮
<!-- trellis-session: v=2 fp=0ddc678cb6233a27 -->

**Date**: 2026-10-06
**Task**: 统一一级二级页面全选按钮
**Branch**: `master`

### Summary

未压缩与已压缩一级图集列表、二级图片网格共四个页面，统一为圆形玻璃勾选按钮，点击切换全选和取消全选。轻存0.1.15已更新真机。

### Main Changes

- 复用SelectAllAction和既有选择逻辑；一级选择可压缩/可还原图集，二级仅选择当前筛选内可操作项目；无可操作项目禁用按钮。统一48dp触摸区域、右侧边距和选中语义。

### Git Commits

| Hash | Message |
|------|---------|
| `8518260` | fix(ui): unify album and media select-all icon actions |

### Testing

- [OK] assembleDebug编译成功，git diff --check通过；真机90d3e7c6安装Success，版本读取0.1.15/code16。未运行自动化测试；四页实际交互与视觉由用户确认。

### Status

[OK] **Completed**


## Session 17: 修正紧凑类型菜单点击动效
<!-- trellis-session: v=2 fp=d9af58d3f401b60b -->

**Date**: 2026-10-06
**Task**: 修正紧凑类型菜单点击动效
**Branch**: `master`

### Summary

修正未压缩与已压缩图片网格类型菜单动效尺寸不匹配；轻存0.1.16已安装到真机。

### Main Changes

- 根因是selectable的默认反馈绘制在48dp点击层，视觉胶囊只有32dp。外层取消默认indication，内层共享InteractionSource并在圆角裁切内绘制中心扩散ripple，保留点击范围与原生选中语义。同步UI规范。

### Git Commits

| Hash | Message |
|------|---------|
| `4837aac` | fix(ui): clip compact filter feedback to visible capsules |

### Testing

- [OK] assembleDebug成功；git diff --check通过；真机90d3e7c6安装Success，读取版本0.1.16/code17。未运行自动化测试，真机动效观感与深浅色状态待用户确认。

### Status

[OK] **Completed**


## Session 18: 设置二级页间距与返回路径、已压缩图标
<!-- trellis-session: v=2 fp=8c8fe8622ee91f5f -->

**Date**: 2026-10-06
**Task**: 设置二级页间距与返回路径、已压缩图标
**Branch**: `master`

### Summary

轻存0.1.17完成设置二级页标题右移、回收站返回设置、已压缩底部图标改为缩小图片样式。安装包已生成，真机已断开，未安装。

### Main Changes

- AppBar返回按钮与标题默认间距12dp；设置三个二级页面共用系统BackHandler返回设置，弹窗与信息面板优先关闭；回收站页面按钮返回设置并保留底部设置选中；已压缩导航使用PhotoSizeSelectSmall填充/描边图标。同步UI规范。

### Git Commits

| Hash | Message |
|------|---------|
| `7c12910` | fix(ui): refine settings navigation and compressed tab icon |

### Testing

- [OK] assembleDebug成功，git diff --check通过；安装尝试提示device 90d3e7c6 not found，devices列表为空。产物artifacts/qingcun-0.1.17-debug-20261006.apk；未运行自动化测试，真机布局、图标和返回交互待确认。

### Status

[OK] **Completed**


## Session 19: 轻存全应用交互动效
<!-- trellis-session: v=2 fp=dbd8118c565c11f9 -->

**Date**: 2026-10-06
**Task**: 轻存全应用交互动效
**Branch**: `master`

### Summary

按emil-design-eng为轻存实现页面、导航、按压、多选、筛选、档位、开关、列表、统计、进度、结果提示和确认弹窗的业务动效。0.1.18/code19安装包已生成，真机未连接。

### Main Changes

- 新增统一Motion策略：120/150/220ms缓出、系统动画关闭/节电/键盘回退、主题瞬时切色；当前页过渡避免保留旧可点击页面；共享按压与颜色反馈；网格仅位置变化；真实进度平滑与静态真实数字；提示可被新消息即时替换；确认立即执行且退出时禁用防重复。设计矩阵与UI规范已保存。

### Git Commits

| Hash | Message |
|------|---------|
| `472f63e` | feat(ui): add coherent motion to navigation and media workflows |

### Testing

- [OK] 最终assembleDebug成功（3s），git diff --check通过；未运行自动化测试。ADB5038设备列表为空，未安装真机。产物artifacts/qingcun-0.1.18-debug-20261006.apk；真机动效/慢速回放/大图库性能/主题与字体/回退实际表现待用户确认。

### Status

[OK] **Completed**


## Session 20: GitHub 图文 README 与正式 APK 发布
<!-- trellis-session: v=2 fp=0559741907d6a626 -->

**Date**: 2026-10-06
**Task**: GitHub 图文 README 与正式 APK 发布
**Branch**: `master`

### Summary

编写核心页面图文 README，使用独立 Android 16 模拟器取得 11 张真实截图；固定正式签名与 Actions 发布流程已推送，v0.1.18 正式发布并完成下载校验。

### Main Changes

- README 覆盖总览、图集、网格、多选、压缩确认、还原、媒体详情、独立档位、过滤和回收站；使用合成演示媒体，说明格式、时间、备份占用与签名升级边界。
- 新增版本标签发布工作流，Gradle 环境变量签名；签名资料仅在忽略目录和 Actions Secrets 保存，第三方 Actions 固定提交，已有 Release 不覆盖。

### Git Commits

| Hash | Message |
|------|---------|
| `1d490f7` | feat(release): add illustrated README and signed Android release workflow |
| `0178c78` | docs: record verified v0.1.18 GitHub publication |

### Testing

- [OK] assembleRelease 成功；11 张 PNG、README 链接与 GitHub Markdown 渲染、YAML、差异和忽略规则检查通过；未另行运行测试套件。
- [OK] Actions 37350484048 success，正式 Release v0.1.18 包含 APK/校验值/公开证书；下载 APK SHA-256 与清单一致，apksigner 和 aapt 验证通过。

### Status

[OK] **Completed**

### Next Steps

- 真机正式版安装与播放由使用者按签名兼容性安排；当前未卸载调试版，整体应用任务保持进行中。


## Session 21: 压缩优化、单项取消与回收站顶栏
<!-- trellis-session: v=2 fp=b9aafeb812564e5a -->

**Date**: 2026-10-06
**Task**: 压缩优化、单项取消与回收站顶栏
**Branch**: `master`

### Summary

完成0.1.19/code20：合并备份摘要读取、减少JPEG大块拷贝和全库分组开销，匹配硬件编码器；压缩支持仅当前项取消回退，回收站删除入口移至右上角。

### Main Changes

- 取消用协作信号和NonCancellable回退，当前项账本发布回调纳入提交边界，历史记录保留；临时文件和编解码资源释放，当前事务按FileTime保留修改时间精度，补齐HEIC取消恢复路径。
- 统一跨页面取消栏，取消中禁用；回收站顶栏删除图标保留含数量/体积的永久清理确认；README和UI规范同步，APK保存在忽略目录。

### Git Commits

| Hash | Message |
|------|---------|
| `18db85f` | feat(compress): reduce processing overhead and safely cancel current item |

### Testing

- [OK] 最终assembleDebug/assembleRelease成功（53秒）、签名校验和git diff --check通过；本地模拟器安装成功。未新增或运行自动化测试套件。
- [OK] 合成素材手动验收：同批次已完成图片保留、取消编码中的视频及后续视频内容/mtime秒值/inode不变；首页取消复验后17条完成账本不变；JPEG熵数据与旧组装器相同、原元数据段相同；顶栏清理弹窗核对17份/114.2MB后取消。
- [OK] 60秒软件视频样本旧11.971秒、最终12.482秒，没有证实模拟器视频提速；撤回13.781秒的1ms轮询尝试，保留减少读取/拷贝/分组与硬件匹配优化。

### Status

[OK] **Completed**

### Next Steps

- 真机速度、OEM/HDR播放、HEIC取消、写入/落账时取消和故障恢复仍需实测；整体应用任务继续进行。


## Session 22: 回收站大量内容闪退与全部清理修复
<!-- trellis-session: v=2 fp=111792e6b6208f31 -->

**Date**: 2026-10-06
**Task**: 回收站大量内容闪退与全部清理修复
**Branch**: `master`

### Summary

轻存0.1.20：固定回收站顶栏、按需绘制备份列表，统一全部清理范围并核验文件删除结果。

### Main Changes

- LazyColumn稳定编号、固定删除按钮；IO清理、64项登记与进度；失败保留索引，到期Worker复用。

### Git Commits

| Hash | Message |
|------|---------|
| `9665780` | fix(recycle): virtualize backup list and verify bulk deletion |

### Testing

- [OK] Android16独立虚拟机复现旧版5018条布局异常；新版5019条正常打开滚动并清理，模拟失败保留1项，重试清空；29个媒体SHA256不变。
- [OK] Debug及签名Release构建通过，lintVital通过，版本21/0.1.20，正式签名一致，git diff --check通过。未运行自动化测试套件。

### Status

[OK] **Completed**

### Next Steps

- 用户自行真机验收；本次仅本地APK与Git提交，不推送或发布。


## Session 23: HEIC丢图风险修复与全局失败恢复保护
<!-- trellis-session: v=2 fp=fef8d8963105eba9 -->

**Date**: 2026-10-07
**Task**: HEIC丢图风险修复与全局失败恢复保护
**Branch**: `master`

### Summary

轻存0.1.21：停止不安全HEIC转换，原片改写前持久保存恢复记录，保护异常及唯一备份，并提供原片恢复和旧备份找回。

### Main Changes

- 移除删除媒体URI重建索引；还原前校验备份和保存当前版本，完整同步与账本通过后清理；Failed停止后续项。

### Git Commits

| Hash | Message |
|------|---------|
| `baf19a3` | fix(media): protect originals and persist recovery on failed operations |

### Testing

- [OK] 独立Android16虚拟机MediaSafetyTest 9项通过：HEIC完整性、日期及账本失败、取消、坏备份、唯一备份保护、还原重试、写中断模拟及旧HEIC/JPEG双文件保护。
- [OK] 原生UI恢复中断JPEG及导出用户HEIC副本成功，SHA256一致且原备份保留；Debug、签名Release、专项测试构建及lintVital通过；git diff --check通过。

### Status

[OK] **Completed**

### Next Steps

- 用户手机已经丢失的照片未直接恢复，旧备份尚在时可通过新版回收站找回；HEIC原格式压缩与MPF扩展仍待后续实现。本次未推送或发布。


## Session 24: 弹出报错同批次与文本行去重
<!-- trellis-session: v=2 fp=4e114f7da5a2fa7c -->

**Date**: 2026-10-07
**Task**: 弹出报错同批次与文本行去重
**Branch**: `master`

### Summary

轻存0.1.22：相同批次原因按首次出现顺序去重，弹出提示重复行合并。

### Main Changes

- AppViewModel使用LinkedHashSet收集原因；RootScreen去空白空行和重复文本行；项目计数及下一次操作反馈保留。

### Git Commits

| Hash | Message |
|------|---------|
| `66f14e6` | fix(ui): deduplicate repeated batch error messages |

### Testing

- [OK] Debug和签名Release构建、lintVital、正式APK签名验证及git diff --check通过。未新增或运行测试，未进行真机视觉验收。

### Status

[OK] **Completed**

### Next Steps

- 安装包本地交付；MPF扩展待继续，未推送或发布。


## Session 25: 双图MPF照片压缩与HDR保留
<!-- trellis-session: v=2 fp=0685b51df1621ca2 -->

**Date**: 2026-10-07
**Task**: 双图MPF照片压缩与HDR保留
**Branch**: `master`

### Summary

轻存0.1.23支持静态双图MPF主图压缩，保留HDR辅助图、元数据与厂商尾部；两份样本副本通过API36原生压缩和字节级还原验收，生成正式及调试APK。

### Main Changes

- 增加真实JPEG边界扫描、严格MPF索引解析及重组；沿用质量档位、唯一标记、备份和安全原地事务。
- 补充README、MPF规范、需求决策与匿名验收记录；个人媒体、诊断样本、APK及签名材料保持忽略。

### Git Commits

| Hash | Message |
|------|---------|
| `866c40c` | feat(jpeg): compress dual-image MPF photos while preserving HDR |

### Testing

- [OK] 原生APP压缩两份样本副本，分别减少58.3%和53.0%；HDR/Display P3、增益参数、元数据、尾部、路径、inode、mtime及MediaStore日期身份一致。
- [OK] 原生APP还原两份备份，与原片逐字节一致；正式及调试构建成功，正式签名检查通过，最终调试APK覆盖安装API36虚拟机成功。未新增或运行单元测试。

### Status

[OK] **Completed**

### Next Steps

- 由用户在真机检查HDR观感；文件创建时间须单独验收，虚拟机日期身份核对不替代该要求。


## Session 26: 无收益图片移入已压缩并标记已跳过
<!-- trellis-session: v=2 fp=85437457b4116a74 -->

**Date**: 2026-10-07
**Task**: 无收益图片移入已压缩并标记已跳过
**Branch**: `master`

### Summary

轻存0.1.24统一无收益提示，普通和实况图片原片不改写，仅持久登记SKIPPED，移入已压缩页，提供标记和筛选；构建、签名与API36原生验收通过。

### Main Changes

- 无收益结果使用类型标记；沿用v5账本字段保存零收益无备份的SKIPPED记录，原文件不写XMP、不创建备份。
- 两页共用有效账本规则，跳过筛选、图集摘要和信息面板区分已跳过与超期/已清理；更新README、需求、设计与执行规范。

### Git Commits

| Hash | Message |
|------|---------|
| `7d0078e` | feat(media): archive photos skipped without size reduction |

### Testing

- [OK] 合成低质量JPEG无收益归类SKIPPED，高质量JPEG成功归类DONE，PNG无处理记录；摘要、inode、纳秒mtime、媒体库ID和日期保持。重启与筛选通过，混合图集还原仅处理成功项，字节恢复一致。
- [OK] 调试/正式构建、发布必需Lint、正式签名、虚拟机覆盖安装及diff检查通过。全量lintDebug失败：既有VideoTranscoder 358/377两处WrongConstant，HEAD原文与零差异已确认，按Trellis范围规则记录而不扩大修复。未新增或运行测试套件。

### Status

[OK] **Completed**

### Next Steps

- 过去未登记的无收益图片需下一次尝试才生成跳过记录；登记取消时序、数据库故障和外部改写等专项边界未宣称已验证。


## Session 27: 轻存0.1.24真机覆盖安装
<!-- trellis-session: v=2 fp=5669b9e94461cd73 -->

**Date**: 2026-10-07
**Task**: 轻存0.1.24真机覆盖安装
**Branch**: `master`

### Summary

按用户要求在真我GT7 Pro将正式版0.1.20覆盖升级至0.1.24，签名一致，安装成功并启动。

### Main Changes

- 更新真实部署记录；使用现有5037 ADB连接，应用数据保留，不操作个人照片。

### Git Commits

| Hash | Message |
|------|---------|
| `79b1b5f` | docs: record Qingcun 0.1.24 installation on GT7 Pro |

### Testing

- [OK] 安装返回Success；读取versionName=0.1.24/versionCode=25；MainActivity启动Status: ok，进程存在。

### Status

[OK] **Completed**

### Next Steps

- 真机压缩功能与HDR观感由用户自行验证；本次仅部署及启动核对。


## Session 28: 多图MPF与已编辑实况主图压缩
<!-- trellis-session: v=2 fp=6fbdbbf4bdc1bce9 -->

**Date**: 2026-10-07
**Task**: 多图MPF与已编辑实况主图压缩
**Branch**: `master`

### Summary

轻存0.1.25按全部MPEntry处理多图，三图及以上实况保留Original和视频；真实三图副本及合成四五图原生压缩、完整还原验收通过。

### Main Changes

- 通用Plan保存所有辅助图索引，校验JPEG/范围/依赖并平移全部offset；三图实况在旧布局解析前分流，外层主图重编码，其余后缀保持。
- 更新多图MPF规范、README、需求决策和匿名验收记录；原始照片、提取视频、诊断输出及APK在忽略目录。

### Git Commits

| Hash | Message |
|------|---------|
| `3bff77b` | feat(jpeg): support multiple MPF images and preserve edited live photos |

### Testing

- [OK] 真实三图20,806,316至17,984,847字节，减少13.6%；合成四五图分别减少66.1%和66.0%。所有索引、辅助图、Original、视频、尾部、元数据及HDR/色彩/身份/日期核对通过。
- [OK] 三份原生APP还原与原片逐字节一致，内嵌HEVC/AAC完整解码通过；正式/调试构建、发布必需Lint、签名及diff检查通过。全量lintDebug仍为既有视频模块两处WrongConstant，未扩大修复；未新增或运行测试套件。

### Status

[OK] **Completed**

### Next Steps

- 真机厂商相册的多图关联、HDR与实况播放由用户验证；合成四五图不替代所有厂商样本验收。


## Session 29: 失败照片事务回滚与回收站职责调整
<!-- trellis-session: v=2 fp=eeb5bb2e449e0920 -->

**Date**: 2026-10-07
**Task**: 失败照片事务回滚与回收站职责调整
**Branch**: `master`

### Summary

轻存 0.1.26：失败事务回滚并保留在未压缩页面，回收站只展示成功压缩备份，删除找回照片板块。

### Main Changes

- 独立清除压缩记录、验证原片备份、恢复内容与日期，完整回滚后清理本次临时备份；未完成恢复在未压缩页提供详情重试，保护备份。
- 共享媒体处理锁防止恢复与清理竞争，修正旧原片已回滚但已压缩记录残留；清理提示区分保护和实际删除失败。

### Git Commits

| Hash | Message |
|------|---------|
| `65cdcb2` | fix(media): roll failed transactions back into uncompressed photos |

### Testing

- [OK] Android 16 原生模拟器注入数据库写入失败：原片 SHA、inode、纳秒修改时间及媒体库日期保持，仍在未压缩页，无残留本次备份；损坏备份拒绝写回，日期同步失败保留原片及备份。
- [OK] Debug 和 Release 构建成功，正式 APK 签名与版本 0.1.26/code27 已核验，最终 Debug APK 安装模拟器启动成功；差异检查通过。全量 Lint 存在未改动视频模块的两项既有 WrongConstant 错误；未新增或运行测试套件，仅更新现有安全断言。

### Status

[OK] **Completed**

### Next Steps

- 137 份真实历史备份尚未逐项核验，未知备份继续保护；本轮未安装真机、未操作真实个人媒体。


## Session 30: 已压缩图集仅显示还原状态
<!-- trellis-session: v=2 fp=cdd071528cd4154c -->

**Date**: 2026-10-07
**Task**: 已压缩图集仅显示还原状态
**Branch**: `master`

### Summary

轻存 0.1.27：已压缩图集只显示可还原 N 项或备份已不可还原，移除已跳过数量。

### Main Changes

- 修改 AlbumDoneUi.statusLine 与不可还原颜色条件，保留图片跳过标记、筛选及媒体处理规则；同步需求和规范。

### Git Commits

| Hash | Message |
|------|---------|
| `d93a3e4` | fix(ui): show only restore availability in compressed albums |

### Testing

- [OK] Debug/Release 构建成功，正式 APK 签名、包名和版本 0.1.27/code28 检查通过；差异检查通过，未新增或运行测试套件，未进行设备 UI 验收。

### Status

[OK] **Completed**

### Next Steps

- 需要真机安装时使用 artifacts/qingcun-v0.1.27.apk；本轮未安装真机。


## Session 31: 压缩PNG开关与安全JPEG转换
<!-- trellis-session: v=2 fp=97277cadb202c4eb -->

**Date**: 2026-10-07
**Task**: 压缩PNG开关与安全JPEG转换
**Branch**: `master`

### Summary

轻存 0.1.28：默认关闭的压缩PNG开关，开启后按普通照片档位转 JPEG，保留原 PNG 的备份/还原与失败事务回滚。

### Main Changes

- Room v6 非破坏迁移；PNG 候选与引擎双重门控；JPEG/HEIF/PNG 格式说明；扫描水位定向更新避免覆盖新设置。
- PNG CRC/像素/透明度检查，EXIF/XMP 迁移及其余块 APP15 原样归档；同目录改后缀，保持媒体 ID/inode，持久转换恢复记录与当前版本安全副本。

### Git Commits

| Hash | Message |
|------|---------|
| `3f7519a` | feat(media): add opt-in PNG to JPEG compression with safe restore |

### Testing

- [OK] API36 原生三档 PNG→JPEG、无EXIF/null日期、元数据/标记、路径/身份/纳秒mtime/媒体日期、四项完整还原通过；合成样本减少89.2/93.1/95.2%，不推断真实照片收益。
- [OK] 真实SQLite登记失败回滚、原生取消仅当前项、透明/APNG/同名保护、持久恢复记录及null日期校验通过；原始5020条账本保留，无新增PNG备份或恢复残留。最后版本重复数据库失败通过，临时触发器和过滤均恢复。
- [OK] 调试/正式构建、正式签名/包名/版本0.1.28-code29、模拟器覆盖安装和冷启动通过，差异检查通过。全量Lint两项既有VideoTranscoder WrongConstant错误，模块未改动；未新增或运行测试套件，仅更新既有PNG判类断言。

### Status

[OK] **Completed**

### Next Steps

- 真机厂商改名兼容性、特殊PNG与文件创建时间待验证；本轮未安装真机、未操作真实个人照片，旧保护备份不因本次功能清理。


## Session 32: 轻存0.1.28正式版真机部署
<!-- trellis-session: v=2 fp=ec2f910ef678faf8 -->

**Date**: 2026-10-07
**Task**: 轻存0.1.28正式版真机部署
**Branch**: `master`

### Summary

按用户要求，将正式签名轻存0.1.28覆盖安装到已连接的RMX5010/Android16真机。

### Main Changes

- 补充PNG功能真机部署记录；保持当前应用安装数据，不卸载或清空。

### Git Commits

| Hash | Message |
|------|---------|
| `1bf20d6` | docs: record Qingcun 0.1.28 device deployment |

### Testing

- [OK] 安装返回Success；系统包信息由0.1.24/code25更新到0.1.28/code29，MainActivity冷启动Status ok，后续读取进程仍存在；差异检查通过。

### Status

[OK] **Completed**

### Next Steps

- 用户在真机自行验证PNG转换、元数据和还原；本轮仅安装及启动，未手动触发压缩、还原、清理。启动执行既定扫描/恢复流程。


## Session 33: 已压缩图集卡片显示节省空间
<!-- trellis-session: v=2 fp=9417a8bbe2432c6a -->

**Date**: 2026-10-07
**Task**: 已压缩图集卡片显示节省空间
**Branch**: `master`

### Summary

轻存0.1.29：已压缩图集卡片第二行改为数量和负值节省空间，例如1,137项 · -4GB。

### Main Changes

- 新增紧凑体积格式化，去掉单位空格和多余小数零；汇总非接管记录非负节省，跳过为零，全部原体积未知显示横线；页头和二级占用信息保持。

### Git Commits

| Hash | Message |
|------|---------|
| `7341453` | fix(ui): show saved space as negative values in album cards |

### Testing

- [OK] 调试/正式构建、正式签名和包名/版本0.1.29-code30检查通过；API36模拟器覆盖安装/启动及原生卡片单行数值验证通过，差异检查通过。未新增或运行测试套件，本轮未重跑全量Lint。

### Status

[OK] **Completed**

### Next Steps

- 真机当前未连接，新正式APK位于artifacts/qingcun-v0.1.29.apk，尚未部署真机。
