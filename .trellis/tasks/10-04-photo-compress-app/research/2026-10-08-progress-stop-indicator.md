# 进度条右端圆点（0.1.34）

## 原因与修改

截图红框里的圆点为 Material 3 LinearProgressIndicator 默认绘制的轨道终点标记。关闭该组件的 drawStopIndicator 即可移除，不需要覆盖绘制、裁切控件或改变进度计算。

通过代码图谱、索引覆盖检查及 app/src/main 范围的调用搜索，找到三个进度控件调用：首页有确定进度的扫描条、扫描总数未知时的不确定条、压缩/还原共用 ActionBar 中的确定进度条。两个确定进度调用均显式传入 drawStopIndicator = {}；不确定条没有此圆点，维持原行为。数量/占用对比条为统计图形，不改变。

保留进度 lambda、motionFloat 平滑、真实计数、主题颜色、圆角、轨道间距及组件原生无障碍语义。业务处理和媒体文件操作未改动。

## 验证

- assembleDebug 成功（5 秒），确认当前依赖支持该绘制参数。
- 最终 APK 包名 com.photocompress.app，versionName 0.1.34，versionCode 35；apksigner 验证通过，沿用当前真机调试签名。
- RMX5010 从 0.1.33/code34 覆盖安装返回 Success；读回 0.1.34/code35，MainActivity 冷启动 Status: ok，TotalTime 1005 ms。
- 启动后手机处于密码锁屏；用户确认已解锁后，ADB 已查不到设备，USB 再次断开。尚未完成修复后进度条的真机视觉核对，未把源码与构建检查当作截图验收。
- 差异检查通过。本次仅改两个绘制参数及版本、规范、任务记录，未新增或运行测试套件，未重跑全量 Lint。上一轮全量 Lint 的两项 VideoTranscoder WrongConstant 既有错误不在本次范围。
- 未卸载/清空应用数据，未执行个人媒体压缩、还原或回收站清理；原始诊断 XML 仅保存在忽略目录 .tmp-device/。
