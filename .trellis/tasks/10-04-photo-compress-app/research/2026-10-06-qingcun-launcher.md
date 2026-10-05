# 轻存名称与启动图标修复

## 用户要求

APP 名称改为“轻存”，排查图标未生效。

## 根因与变更边界

- 真机实际安装 `com.photocompress.app`，其源工程是 `C:/Project/Code/android-photo-compress`，修复前版本为 0.1.10 / versionCode 11。
- 银白玉绿图标此前接入 `C:/Project/Code/android-compress`，属于另一套工程及包名；当前实际安装包的资源仍是蓝底白色箭头，应用标签仍是“照片压缩”。
- 实际工程的 `mipmap-anydpi-v26/ic_launcher.xml` 是 Android 16 选择的图标资源。必须替换这个资源，避免仅新增无限定目录同名文件而继续选中旧图标。
- 本次修改应用名称资源、首页和权限入口的名称引用，接入已有图标原稿、前景、背景颜色和主题单色轮廓，并更新安装包版本。包名保持原值，覆盖安装保留应用数据；不修改压缩逻辑，不清空启动器数据。

## 实现

- `app_name` 改为“轻存”；首页标题和权限引导使用同一名称资源。
- 普通图标和圆形图标沿用 Manifest 的 `@mipmap/ic_launcher` 引用，替换实际选择的 v26 资源。
- 原样复制银白玉绿 PNG，采用已有 2dp 留边前景及深石墨背景，加入单色轮廓。
- 原稿 SHA-256：`2e60a57f1401b16a8c420a0aae917b22eb7aa2a374830e850de52f48664d8df8`。
- 新版为 0.1.11 / versionCode 12。

## 实际检查

- `:app:assembleDebug` 成功，耗时 7 秒；未运行测试套件。
- APK badging 确认包名 `com.photocompress.app`、应用标签“轻存”与 0.1.11 / versionCode 12。
- APK XML 确认选中的自适应图标含 background、foreground、monochrome 三层。
- APK 内图标 PNG 与源原稿逐字节一致，摘要与上述值相同。
- ADB 覆盖安装返回 `Success`；设备包信息读回 0.1.11 / versionCode 12。
- 真我 GT7 Pro 原厂桌面截图确认右下方应用显示银白玉绿图标，名称“轻存”，无需清空桌面或卸载应用。
- 桌面截图保存在忽略目录 `local-device-reports/`，不提交个人桌面内容。
- 差异检查通过。主题图标着色未单独切换验证。

## 安装包

`artifacts/qingcun-0.1.11-debug-20261006.apk`（开发签名安装包，按规则忽略）。

SHA-256：`51e027338cc4b6a368b9ecf473daa2af04d26e8417f2c0822d9046f19c7553c7`。
