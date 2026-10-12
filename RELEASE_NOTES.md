Beacon v1.1.0 提升推送重试可靠性，并新增消息来源、分组和有效期。

- **发送幂等**：原生与 Bark 接口支持 `Idempotency-Key`。同一凭据下，同键同内容在七天内返回原消息；同键不同内容返回 HTTP 409。消息确认或历史清理后，幂等结果仍保留至窗口结束。
- **消息来源与分组**：手机保存应用 ID、应用名称和 `group`，消息卡片显示标签，点击标签筛选；搜索支持应用名称、分组及 `app:` / `group:` 精确筛选。历史导出、导入保留这些信息。
- **消息有效期**：推送支持 `ttl`，单位为秒，范围 0–604800。省略或设为 0 使用七天默认期限；过期消息停止 HTTP 和 WebSocket 投递，已收到的手机历史继续保留。
- **兼容升级**：Go、Workers 和 Android 自动迁移已有数据库，保留设备、消息和接收状态。旧请求无需修改；使用新字段需要先升级服务端。

升级前备份服务端数据库或存储。建议先升级服务端，再升级 Android App。

- **Android**：下载 `beacon-release.apk` 安装（Android 8.0 及以上）。
- **服务端**：下载 `beacon-server-linux-amd64` / `beacon-server-linux-arm64`，配套 `docker-compose.yaml`；也可使用 `ghcr.io/zerohomers0/beacon-server:latest`。
- **Cloudflare Workers**：更新仓库后重新部署 `workers/`，保留原 `ADMIN_TOKEN` 和 `BEACON_INSTANCE`。

`SHA256SUMS-android` 和 `SHA256SUMS-server` 提供下载文件校验值。
