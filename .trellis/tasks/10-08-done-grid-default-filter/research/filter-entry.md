# 已压缩网格标签误切换

## 定位与证据

- `AppViewModel.openAlbum` 和 `backToAlbums` 原本就设置 `filter="all"`；状态更新使用 `MutableStateFlow.update`，扫描/设置更新保留现有分页状态。
- `DoneLevel2` 的标签选中值来自 `level.filter`；生产代码唯一选中其他筛选的写入入口是 `setFilter`，由 `FilterChips.selectable.onClick` 调用，没有按图集 SKIPPED 数量自动切换的分支。
- `MotionPage` 用 `(page, depth, album)` 作为路由 key，只展示当前页，但新页从第一帧即可接收触摸。`AlbumCard.combinedClickable` 点击后立即导航。因此快速重复点击图集的后续触摸有机会命中新页筛选栏，选择“已跳过”。这是源码支持的可信触发机制；本轮未能在真机复现，不将该机制表述为已经实测确认。

## 修改

- 只在已压缩网格的 `FilterChips` 开启进入触摸保护。
- 记录筛选栏初次进入的单调时间，使用系统 `LocalViewConfiguration.doubleTapTimeoutMillis`，不新增固定的全局防抖时长。
- 在 `PointerEventPass.Initial` 接收触摸。按下发生在进入窗口内时，消费按下及整次后续事件；即使抬起时已超过窗口，也不能触发标签点击。
- 窗口后不消费事件，正常筛选和横向滚动；不改变视觉、标签 selected/Role.Tab、键盘及无障碍点击。关闭动画时仍有保护。
- 路由 key 在返回后重进/更换图集时重建筛选栏；改变标签和扫描更新不会重建进入时间。没有用重复重置全部标签的副作用覆盖用户选择。

## 检查与边界

- 调试构建成功，Kotlin/Compose 编译通过。
- 全量 `lintDebug` 成功；使用已有 `.gradle/local-build-home` 绕过本机全局 init.gradle 的仓库冲突，未修改全局配置。
- 本次差异检查通过；未新增或运行测试套件。
- 连接的 RMX5010 处于安全锁屏，已请求用户解锁但本轮未获得可交互屏幕。单击、快速连点、返回重进、主动筛选、关闭动画的真机回归均未执行；未安装本次构建包、未处理个人媒体。
- 构建基于当时工作区，含另一项 HEIC/压缩性能工作；本次只暂存标签修复及其文档，不纳入其他变更，不修改版本号或推送。
