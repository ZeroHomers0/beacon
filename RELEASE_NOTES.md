Beacon v1.1.1 修正 Android 更新提示红点的位置。

- 更新红点紧贴设置图标右上角显示，避免与设置按钮距离过远。
- MIUIX 和 Material 两种界面风格共用图标角标布局。
- 保持设置按钮原有点击范围与导航操作。

Android App 可直接覆盖安装升级。已有 v1.1.0 服务端可继续使用。

- **Android**：下载 `beacon-release.apk` 安装（Android 8.0 及以上）。
- **服务端**：提供 `beacon-server-linux-amd64` / `beacon-server-linux-arm64`、`docker-compose.yaml` 和镜像 `ghcr.io/zerohomers0/beacon-server:latest`。
- **Cloudflare Workers**：部署方式见仓库文档。

`SHA256SUMS-android` 和 `SHA256SUMS-server` 提供下载文件校验值。
