# 轻存 Roomy 介绍短片

22 秒横版中文产品短片，1920 × 1080 / 30 fps，配乐与克制音效，无旁白。

## 文件

- `brag.mp4`：最终成片。
- `brag.jpg`：稳定品牌画面封面，也写入 MP4 的第一帧。
- `brag-plan.md`：产品主张、素材来源及分镜。
- `composition-brief.md`：制作约束。
- `share-copy.txt`：发布配文。
- `composition/`：可编辑的 Hyperframes 项目，CLI 固定为 0.8.143。
- `verification.md`：实际检查记录。

## 素材来源

`composition/assets/ui/device-quality.png` 来自 2026-10-09 已连接的 RMX5010 真机，轻存版本 0.1.39。仅进入压缩比例设置页并截图，裁除状态栏；未修改档位或执行媒体压缩、还原、删除操作。

其他界面来自项目已有的 `docs/screenshots/`，为轻存 0.1.18 的 Android 16 模拟器真实截图。照片来自项目合成演示媒体，不含个人照片。片中的 38.3 MB 是这组演示媒体当时处理的真实节省结果，不能视作任意素材的收益保证。

应用图标为 `docs/assets/app-icon.png` 的正式图标。音乐来自 brag 技能附带的 ende.app “Happy Beats / Business Moves vol.12”；音效来自技能附带的 Kenney CC0 资源。来源和已知许可说明见 `composition/assets/LICENSES.md`。音乐库没有附带具体授权条款，公开发布前需确认音乐授权。字体使用 macOS 本机系统字体，源文件未包含系统字体文件。

## 本地预览和重制

需要 Node.js 22+、FFmpeg 和可运行的 Chromium。默认 macOS 中文系统字体可用；其他平台需安装可显示中文的系统字体。

在 `composition/` 中运行 `npm run check` 完成检查，再运行 `npx --yes hyperframes@0.8.143 preview --background` 打开时间线。最终导出使用 `npx --yes hyperframes@0.8.143 render --quality delivery --output ../brag.mp4`。

选择稳定画面作为封面后，按 brag 技能交付规则替换首帧像素，保留其余帧、时长和音频。检查过程截图、缓存、设备原始截图及原始 UI 层级不进入版本管理。
