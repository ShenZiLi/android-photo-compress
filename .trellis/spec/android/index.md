# 安卓项目规范入口

本项目为 Android 工程 `android-photo-compress`，采用 Kotlin / Compose；规范随实际实现和设备验证继续补充。

## 当前已经确定的约定

- [本地 Git 提交](git-workflow.md)：用户要求每个完成的变更单元提交到本地 Git。
- [液态玻璃 UI](ui-style.md)：材质、透明容器文字、禁用状态、触摸目标与用户已确认文案。
- [原地改写后的媒体库同步](media-store-refresh.md)：原厂实况索引、日期与条目身份校验。

- [媒体错误恢复](media-recovery.md)：持久恢复记录、异常备份保护、HEIC 安全跳过与恢复故障验收。
- [多图 MPF 照片](mpf-photo.md)：全部 MPEntry 重建、真实 JPEG 边界、HDR/内嵌 Original/视频与厂商尾部保留。
- [无收益图片状态](skipped-photos.md)：SKIPPED 持久记录、未压缩/已压缩分页、零收益与不可还原规则。
- [PNG 转 JPEG](png-jpeg.md)：默认关闭的开关、普通照片档位、元数据迁移与转换/还原事务。
- [HEIC 原格式压缩](heic.md)：主图 HEVC 重编码、原容器信息保留、写前校验与同媒体事务。
- [压缩性能](compression-performance.md)：单次主图编码、硬件优先、音频/附加数据直通与读取拷贝边界。
- [压缩期间屏幕常亮](screen-awake.md)：压缩批次持有、取消与收尾释放、界面生命周期与后台行为。
- [媒体库派生快照](library-snapshot.md)：新结果发布条件、过期计算拒绝与扫描/设置更新回归；回收站备份列表由账本直接派生，清理收尾不重扫。

## 开发前检查

1. 阅读当前任务 `prd.md`、`design.md`、`implement.md` 及对应资料。
2. 区分已经批准的要求、待评审的设计、未验证的设备能力。
3. 每个任务结束检查差异、必要验证与本地提交，报告实际结果。

Trellis 默认的 `frontend/`、`backend/` 是初始化模板；本项目没有 Web 前端或服务端代码，不能套用 React、TypeScript 或服务器惯例。新增能力落地后继续增加对应真实示例。
