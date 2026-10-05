# 安卓项目规范入口

本项目为 Android 工程 `android-photo-compress`，采用 Kotlin / Compose；规范随实际实现和设备验证继续补充。

## 当前已经确定的约定

- [本地 Git 提交](git-workflow.md)：用户要求每个完成的变更单元提交到本地 Git。
- [原地改写后的媒体库同步](media-store-refresh.md)：原厂实况索引、日期与条目身份校验。

## 开发前检查

1. 阅读当前任务 `prd.md`、`design.md`、`implement.md` 及对应资料。
2. 区分已经批准的要求、待评审的设计、未验证的设备能力。
3. 每个任务结束检查差异、必要验证与本地提交，报告实际结果。

Trellis 默认的 `frontend/`、`backend/` 是初始化模板；本项目没有 Web 前端或服务端代码，不能套用 React、TypeScript 或服务器惯例。新增能力落地后继续增加对应真实示例。
