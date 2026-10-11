# Beacon

自托管 Android 消息推送应用，提供 Go/SQLite 与 Cloudflare Workers 两种服务端。

服务端可以是自己服务器或 NAS 上的一个 Docker 容器，也可以是 Cloudflare Workers 上的一个 Worker；手机装一个 APK，用一次性绑定码把两者连起来，之后任何脚本、CI、NAS 都能用一条 HTTP 请求把通知推到手机上。消息不经过任何第三方推送通道。

安装包与两种服务端的二进制、`docker-compose.yaml` 都在 [Releases](https://github.com/ZeroHomers0/beacon/releases) 下载。源码目录：`android/`（Kotlin + Jetpack Compose 客户端）、`server/`（Go + Gin + SQLite，纯 Go、CGO 关闭）、`workers/`（Cloudflare Workers + Durable Objects）。三端共用同一套 `/api/v1/*` 协议，App 里换服务器地址就能切换后端。
