Beacon v1.1.2 优化 Android 冷启动和后台接收效率。

- 首屏优先读取本地消息，服务器检测并行执行，慢网络不阻塞本地列表和接收状态操作。
- 服务连接验证完成前不提前排队后台状态请求，减少启动时重复工作。
- 短暂读取不再闪现整页“正在读取数据”；较慢读取仍显示进度，读取失败保留重试入口。
- 合并即时接收的通知刷新与批量回执数据库操作，降低批量消息的后台开销。
- 优化通知图片缓存和网络变化处理。
- 后台权限页精简为自启动和省电策略无限制，移除锁定后台卡片引导。
- 修复后台返回时自动刷新覆盖阅读位置、关闭通知权限时“仅保存”规则失效的问题。

Android 版本号：1.1.2（versionCode 8）。可直接覆盖安装，已有 v1.1.0 服务端可继续使用。

- **Android**：下载 `beacon-release.apk` 安装（Android 8.0 及以上）。
- **服务端**：提供 `beacon-server-linux-amd64` / `beacon-server-linux-arm64`、`docker-compose.yaml` 和镜像 `ghcr.io/zerohomers0/beacon-server:latest`。
- **Cloudflare Workers**：部署方式见仓库文档。

`SHA256SUMS-android` 和 `SHA256SUMS-server` 提供下载文件校验值。
