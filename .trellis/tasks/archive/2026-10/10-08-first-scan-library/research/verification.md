# 首次扫描结果和图集过滤为空：根因与验收

## 根因

`MediaRepository` 将扫描结果返回并写入缓存；`AppViewModel.refresh` 将它们放入 `UiState.items`，随后后台构建 `LibrarySnapshot`。首页、两页图集和 `UiState.allAlbumNames()` 都读取 `state.library`。

原发布条件为 `if (it.library.matches(it))`，比较的是旧派生快照。首次扫描换成非空列表后，旧空库快照必然不匹配，新的派生结果被丢弃，所有上述消费者继续显示空结果。设置变化同样不匹配旧快照，导致排除图集和 PNG 设置不能更新派生结果。另一方面，若当前快照已经匹配当前输入，该条件还会允许过期计算结果覆盖它。

## 修复

`LibrarySnapshot.applyTo(state)` 比较待发布的新快照与当前媒体/账本/恢复日志列表引用及设置值。只有全部一致才 `state.copy(library = this)`，在原 `MutableStateFlow.update` 内执行。保留后台计算和无变化时的 O(1) 命中，导航/选择/进度不作为输入条件。版本为 0.1.37 / code 38。

## 回归结果

在 realme RMX5010 / 90d3e7c6 上运行 `com.photocompress.app.ui.LibrarySnapshotTest`，仅使用内存对象，无数据库及媒体读写：

| 检查 | 原发布条件 | 修复后 |
| --- | --- | --- |
| 首次扫描后分类、数量、占用、图集和设置名单 | 失败 | 通过 |
| 图集隐藏/重新展示与设置名单保留 | 失败 | 通过 |
| PNG 开关立即更新候选且无需重扫 | 失败 | 通过 |
| 四种输入变化后的过期结果拒绝 | 失败 | 通过 |
| 计算期间导航/选择/扫描进度保留 | 失败 | 通过 |
| 空库重新扫描清除旧结果 | 失败 | 通过 |

原逻辑最终基线：6 tests / 6 failures；修复后：`OK (6 tests)`。准备基线时将旧判断原样移动到发布方法，未提前纠正判断；用已一致的快照建立非首次场景，避免测试因空快照而无效通过。

## 构建与检查

- `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest` 成功。
- 现有 JVM 单元测试：72 tests / 0 failures / 0 errors / 0 skipped。
- Lint：0 Fatal / 0 Error / 32 Warning；差异检查通过。
- 本机 SDK 为 mise android-sdk/21.0，JDK 17，Gradle 9.5.1。使用项目已忽略的 `.tmp-device/gradle-user-home` 避开全局初始化脚本与仓库策略冲突，没有修改全局配置。
- 图谱覆盖检查确认两个产品 UI 文件、专项测试和构建配置未记录解析缺口、元数据与当前文件匹配。

## 真机实际界面

- 0.1.37/code38 覆盖安装返回 Success，读取已安装版本一致，冷启动 Status ok；没有卸载或清空应用数据。
- 现有缓存的增量扫描日志为 `scan incremental: 8828 items`。
- 首页实际展示 8,828 项（未压缩 5,021，已压缩 3,807），不再为空。
- 设置页「图集过滤」实际展示共 33 个图集及对应项数，不再为空；只打开页面，没有修改开关。
- 未触发压缩、还原或清理备份。截图和界面树保存在已忽略的 `.tmp-device/`，不提交个人图集信息或媒体。
- 首次全量扫描到非空库的状态转换由合成回归验证；没有为验收首次安装而清空真机数据。
