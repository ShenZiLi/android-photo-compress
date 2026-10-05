# GitHub 图文介绍与首个正式签名发布

## 范围

用户授权编写 GitHub README、用本地安卓虚拟机截图介绍核心页面、提交及推送 Git，并通过 GitHub Actions 发布 APK。整体应用任务仍在进行中，此次不归档。

## 页面与素材

- 独立 AVD `Qingcun_Readme_API36`：Android 16 / API 36、Google APIs x86_64、720 × 1600、280dpi、深色主题。
- 使用 0.1.18 / code 19 调试构建运行页面；正式构建使用相同源代码、不同签名。
- 导入本地合成的 22 项演示媒体：Forest 13 项、Urban 5 项、Studio 4 项。无真实人物、地点、用户媒体或旧模拟器数据。
- `docs/screenshots/` 保存 11 张原生 `screencap` PNG，逐张查看。未裁剪、覆盖 UI 或编造统计。
- 实际压缩 Forest 中 12 张 JPEG：46.2 MB → 7.9 MB；首页显示已省 38.3 MB、总媒体占用 42.5 MB。演示实况在该批次未进入成功账本，保留原文件；截图不宣称模拟器已完成 OEM 实况兼容性验证。
- 检查设置三个子页返回设置；回收站保留 12 份原始备份。
- README 覆盖使用流程、质量档位、去重、备份、架构、构建发布和格式/完整性边界；明确已省统计不包含备份占用、创建时间与 HEIC 路径仍有已知限制。

## 发布配置

- 新增 `.github/workflows/release.yml`：`v*` 标签或手动指定已有标签，校验版本/标签提交、构建正式 APK、验证签名、生成 SHA-256、上传工作流产物并创建 GitHub Release。
- `checkout`、`setup-java`、`upload-artifact` 使用 GitHub API 查得的当前版本并固定到完整提交编号。
- 用户未回复签名偏好，按已说明的推荐方案准备固定正式签名。私钥及密码仅在本机忽略目录和仓库 Actions Secrets 保存；仓库只记录公开配置及证书指纹。
- Gradle 从四个 `SIGNING_*` 环境变量读取签名资料，显式 release 任务缺少资料时拒绝构建。私钥目录 `.signing/` 已忽略。
- 正式签名与现有调试版不同，不能直接覆盖安装；README 和发布说明提醒保留仍有恢复备份的旧版。未卸载真机应用。
- 工作流拒绝覆盖已存在的 Release。

## 本地检查

- `:app:assembleRelease` 成功，1 分 30 秒；包含 Android release 构建自带的 lintVital 阶段，未另行运行测试套件。
- `apksigner verify --verbose --print-certs`：APK Signature Scheme v2 通过，RSA 3072。
- 包名 `com.photocompress.app`，versionName `0.1.18`，versionCode `19`，compile SDK 36。
- 公开证书 SHA-256：`d7bbc9b1a847a07acf479eaeceb63a89e5eeec91fae4ddb7be14c9eae0304b89`。
- 本地 APK SHA-256：`bf86cece3746cfc0dcb65a181282a230d187df4683fc67853dfc322831802b90`。CI 独立构建的 APK 字节摘要可不同，实际下载以 Release 附带摘要为准。
- 11 张 PNG 均可读取、尺寸均为 720 × 1600；README 本地图片与资料链接存在，GitHub Markdown API 渲染成功；工作流 YAML 可解析，事件、权限和 Actions 固定提交配置检查通过。
- `git diff --check` 通过，私钥目录、APK 与临时文件忽略规则检查通过。
- 私钥、原始演示媒体、AVD 数据、APK、诊断输出和临时验证工具均在忽略路径。

## 远端结果

- 工作提交 `1d490f7` 已推送 `master`，注释标签 `v0.1.18` 指向同一提交；GitHub API 确认 README 与全部 11 张截图存在。
- [Actions 37350484048](https://github.com/ShenZiLi/android-photo-compress/actions/runs/37350484048)：`success`，签名构建、签名校验、产物上传、Release 创建和临时私钥清理步骤均成功。
- [轻存 0.1.18 Release](https://github.com/ShenZiLi/android-photo-compress/releases/tag/v0.1.18)：正式发布，非草稿、非预发布；包含 APK、`SHA256SUMS.txt` 和公开 `signing-certificate.txt`。
- GitHub 记录发布时刻 `2026-10-05T17:47:13Z`（北京时间 2026-10-06 01:47）。APK 45,682,294 字节。
- 从实际 Release 下载全部三个产物，本地重新核对 SHA-256 与清单一致：`8c483fe7f9eecfc770be32e4eea3a1dcc92fa8215a6e3bc9d1e73b6704cb34bf`。
- 下载 APK 的 `apksigner` v2 验证通过，公开证书指纹与本机固定正式签名一致；`aapt` 确认包名、versionName 0.1.18、versionCode 19 正确。
- 下载产物在忽略目录保存。此次没有执行真机安装或新增真机播放验收。
