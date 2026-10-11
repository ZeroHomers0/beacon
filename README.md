# Beacon

自托管通知推送。Android 单 APK 接收，服务端可以是一个 Docker 容器，也可以是一个 Cloudflare Workers。

---

## 1 这是什么

Beacon 解决「把消息推到自己手机上」这件事：你自建一个服务端，手机装一个 APK，用一次性绑定码把两者连起来。之后任何脚本、CI、NAS、服务器都能用一条 HTTP 请求把通知发到手机上。

- **不用厂商推送通道**。不经过 FCM、极光或任何第三方，消息只走你自己的服务器。
- **不用 Root，不用框架，不用 Xposed/LSPosed**。就是一个普通 APK，靠前台服务和系统任务调度收消息。
- **消息落盘、可回溯**。服务端保存消息事务与接收回执，App 断线后自动补收，不丢消息；App 本地同样有一份可搜索的历史。
- **两种接收模式**。省电接收按系统调度周期拉取，几乎不耗电；即时接收用 WebSocket 长连接，秒级到达。可以按充电、Wi-Fi、时间段等条件自动切换。
- **三端可选服务端**。Go 单容器（自备服务器 / NAS）或 Cloudflare Workers（无服务器，免费额度足够个人使用），两者协议一致，App 端填哪个地址就连哪个。

适合：自建服务器告警、下载完成通知、脚本执行结果、家庭自动化提醒，以及任何你不想让内容经过第三方推送服务商的场景。

---

## 2 技术架构

```
┌──────────────────────┐   HTTPS + WSS    ┌─────────────────────────────┐
│  Android 客户端       │ ───────────────► │  服务端（二选一）             │
│  Kotlin / Compose    │ ◄─────────────── │  A. Go + Gin + SQLite       │
│  本地 SQLite + 通知   │  协议 1 / 协议 2  │  B. Workers + Durable Object │
└──────────────────────┘                  └─────────────────────────────┘
                                                     ▲
                                      HTTP POST /api/v1/push（发送方：脚本、CI、NAS…）
```

### 客户端（`android/`）

- Kotlin + Jetpack Compose，界面基于 MIUIX 与 Material 3；单一 APK，`io.github.beacon.app`，minSdk 26 / targetSdk 37。
- 省电接收走 WorkManager；即时与规则接收走前台服务 + 原生 WebSocket。
- 本地 SQLite 保存消息与设备凭证，支持搜索、筛选、删除墓碑；通知渠道、声音、免打扰时段可配。
- 内置服务器管理页：绑定码、设备列表、测试推送、发送应用凭证（为每个发送方单独发 token），无需登录网页后台。

### 服务端 A：Go（`server/`）

- Go + Gin + `modernc.org/sqlite`，**纯 Go 实现、CGO 关闭**，产物是单个静态二进制，镜像极小。
- 消息事务落盘、接收回执、过期清理、容量与限流准入、删除墓碑全部在服务端完成。
- 同时提供 HTTP 省电接口与原生 WebSocket 长连接，共用同一个 443 端口。
- 内置 Bark 兼容兜底路由：老 Bark 脚本改一下域名就能直接用。
- 唯一对外状态就是 `./beacon-data` 目录里的 SQLite 文件，备份即拷贝。

### 服务端 B：Cloudflare Workers（`workers/`）

- Workers + Durable Object（内置 SQLite），无需服务器、无需域名也可用 `*.workers.dev`。
- 同一套 App 协议，`/api/v1/*` 与 Go 端一致，App 端只需换服务器地址。
- 建议只作为轻量/灾备后端使用，两者之间暂不提供数据迁移工具。

### 协议与端口

| 项 | 值 |
| --- | --- |
| HTTP 接口协议 | `1`（`/api/v1/*`） |
| 实时配置协议 | `2`（`/api/v1/realtime/config`） |
| 实时流路径 | `/api/v1/realtime/stream`（WebSocket，原生 WSS） |
| 服务端口 | 容器内 `8080`，compose 默认映射到 `127.0.0.1:8088` |
| 管理凭证 | 64 位小写十六进制 token，留空即关闭管理 API |
| 绑定码 | 32 位一次性码，10 分钟有效 |
| 待接收窗口 | 7 天（超时未接收即过期） |
| 回执保留 | 30 天 |
| 单设备容量 | 10,000 条 / 64 MiB 文本 |
| 全局容量 | 100,000 条 / 256 MiB 数据库（超限返回 507） |
| 限流 | 返回 429 `push_rate_limited`，带 `Retry-After: 1` |
| 消息优先级 | 0–10，默认 5；0 只保存不提醒，1–3 静默，4–7 普通，8–10 重要 |
| 请求体上限 | 512 KiB（标题 1 KiB，正文 64 KiB） |

需要重点说明的一条设计：**服务端从不构造绝对 URL**。绑定码、推送路径、实时流路径全部是相对路径，域名只存在于你的反向代理配置和 App 里填的服务器地址中。所以服务端不需要、也不会读取任何域名配置——换域名只要改反代和 App。

---

## 3 三端获取部署

### 3.1 Android 客户端

**直接安装**：到 [Releases](https://github.com/ZeroHomers0/beacon/releases) 下载最新的 `beacon-release.apk`，安装即可。系统会提示「未知来源」，允许后继续。首次启动按 App 内引导填写服务器地址与绑定码。

**自行构建**（需要 JDK 21 + Android SDK 37）：

```bash
cd android
./gradlew :app:assembleRelease       # 产物：app/build/outputs/apk/release/
```

正式签名是可选的环境变量，四个变量必须同时提供，缺一会直接报错 `All four BEACON_RELEASE signing variables are required`：

```bash
export BEACON_RELEASE_KEYSTORE_FILE=/path/to/release.jks
export BEACON_RELEASE_KEY_ALIAS=beacon
export BEACON_RELEASE_STORE_PASSWORD=...
export BEACON_RELEASE_KEY_PASSWORD=...
```

不提供时产出未签名的 release APK，自行 `apksigner` 签名或直接用 debug 包测试。

### 3.2 服务端 A：Go 单容器（推荐）

需要一台能跑 Docker 的机器（VPS、NAS、软路由都行），以及一个指向它的域名 + HTTPS 证书。

**第一步，准备目录并生成管理员 token：**

```bash
mkdir -p /opt/beacon && cd /opt/beacon
mkdir beacon-data
openssl rand -hex 32          # 把输出复制下来，下一步要用
```

**第二步，把 `server/docker-compose.yaml` 拷过来**，然后改两处：

1. `BEACON_WEB_ADMIN_TOKEN`：换成刚才生成的那串 64 位小写十六进制。
2. `BEACON_HISTORY_RETENTION_DAYS`：默认 `0` 表示永久保留已确认的消息；填 `1`–`3650` 会按天做小时级清理。

```bash
curl -O https://raw.githubusercontent.com/ZeroHomers0/beacon/main/server/docker-compose.yaml
vi docker-compose.yaml
```

**第三步，配置反向代理。** 容器只监听 `127.0.0.1:8088`，不直接对外。用你惯用的 nginx / Caddy / Traefik 把域名代理过来，关键是两件事：转发到 `127.0.0.1:8088`，并且放行 WebSocket（`Upgrade` / `Connection` 头）。nginx 最小示例：

```nginx
location / {
    proxy_pass http://127.0.0.1:8088;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 450s;      # 必须大于服务端空闲断连阈值，否则即时接收会反复掉线
}
```

**第四步，启动并自检：**

```bash
docker compose up -d
docker compose ps
curl http://127.0.0.1:8088/healthz
# {"status":"ok","version":"1.0.0","protocol":"1"}
curl https://beacon.example.com/healthz   # 走域名再验一次
```

**不用 Docker 也行**：仓库的 `server/` 是标准 Go module，`CGO_ENABLED=0 go build ./cmd/beacon` 出单个二进制，`-listen 0.0.0.0:8080 -data /path/to/data` 启动即可。

**升级**：`docker compose pull && docker compose up -d`。数据在 `./beacon-data`，镜像升级不动它。

### 3.3 服务端 B：Cloudflare Workers

需要在 [dash.cloudflare.com](https://dash.cloudflare.com) 有一个账号，本地有 Node.js。

```bash
cd workers
npm install

# 管理员密钥（至少 32 字符，建议同样用 64 位 hex）
npx wrangler secret put ADMIN_TOKEN

# 可选：实例名，绑定设备后不要再改；历史保留天数默认 30
# 在 wrangler.toml 的 [vars] 里改 BEACON_INSTANCE / HISTORY_RETENTION_DAYS

npx wrangler deploy
```

部署完终端会打印一个 `https://beacon-server.<你的子域>.workers.dev` 地址，把它填进 App 的服务器地址即可。也可以把自己的域名绑到这个 Worker 上。

> 注意：Worker 的请求日志已关闭（`observability.enabled = false`），因为 Bark 兼容路径的 URL 里会带凭证和消息正文。

---

## 4 简单教程

### 4.1 把手机连上服务器

1. 打开服务端管理：App 里进入「设置 → 服务器配置」，填服务器地址（`https://beacon.example.com`，不要带尾部斜杠和路径）和管理员 token，登录。
2. 在管理页新建一个绑定，会得到一串一次性绑定码（32 位，10 分钟内有效）。
3. 回到 App 的「服务器配置」，填入同一个服务器地址、这串绑定码和设备名称，保存。绑定成功后管理页会多出一台设备。
4. 绑定码是一次性的，用过即废；设备凭证保存在本地，后续升级 App 不会掉线。要换绑就在管理页吊销旧设备再重新绑定。

### 4.2 发一条通知

**最快的方式**（管理页的发送应用里会直接生成对应的 curl 命令）：

```bash
curl -X POST 'https://beacon.example.com/api/v1/push' \
  -H 'Authorization: Bearer <发送应用凭证>' \
  -H 'Content-Type: application/json' \
  -d '{"title":"备份完成","body":"nas 快照已上传","priority":5}'
```

**给不同来源发不同 token**：在管理页为每个发送方（比如「NAS」「CI」「监控」）单独建一个发送应用，各自拿一个凭证。这样可以单独吊销某一个，不用动其它。

**在 App 里直接拿命令**：「服务器配置」页的「推送 API 示例」有四个按钮（curl / PowerShell / JSON curl / JSON PowerShell），点一下就把含推送密钥的完整命令复制到剪贴板；同一页的「复制推送密钥」只复制密钥本身。

**Bark 兼容**：老 Bark 脚本把域名换掉就能继续用，路径形如（第一段就是这台设备的推送密钥，也就是上面那串）：

```bash
curl 'https://beacon.example.com/<推送密钥>/备份完成/nas%20快照已上传'
```

### 4.3 接收模式怎么选

| 模式 | 行为 | 耗电 | 适用 |
| --- | --- | --- | --- |
| 省电接收 | 按系统调度周期拉取 | 极低 | 日常默认，通知不要求秒级 |
| 即时接收 | 常驻 WebSocket，断线自动重试并补收 | 较高 | 需要秒级到达 |
| 规则接收 | 满足条件时切即时，否则回落省电 | 中 | 例如「充电时」或「连 Wi-Fi 时」即时 |

规则模式可以组合充电中、连接 Wi-Fi、指定时间段等条件，全部满足才启用即时接收，不满足时自动回落到省电设置。省电接收**不需要**常驻进程，也不需要关闭电池优化。

### 4.4 收不到通知时排查

1. 先在 App 里点「立即同步」。能收到说明服务器和凭证都没问题，是后台调度被系统拦了。
2. App 的「设置 → 诊断」会列出后台运行、自启动、电池优化等系统状态，照着逐项放行。小米/HyperOS 等厂商的省电策略额外要在「应用设置」里确认。
3. 即时接收模式要确认反向代理放行了 WebSocket；诊断页会显示最近一次即时连接时间与失败类型。
4. 服务器侧先 `curl https://你的域名/healthz`，再确认发送方 token 没被吊销。

---

## 5 疑问

**为什么不用 FCM / 极光 / 厂商推送？**
因为它们都要求消息经过第三方，而 Beacon 的整个前提就是消息只走你自己的服务器。代价是后台收消息要受 Android 系统调度影响，所以 Beacon 提供了即时接收模式作为补充。

**需要 Root、LSPosed 或装框架吗？**
不需要，任何一项都不需要。Beacon 是普通 APK，只使用前台服务、WorkManager 与系统通知这些公开能力。

**服务端不填域名，那绑定码里的地址从哪来？**
服务端刻意不生成绝对地址，只返回相对路径。App 用你填的服务器地址去拼，所以域名只存在于反向代理配置和 App 的输入框里。好处是换域名不用动服务端，也避免服务端被错误配置误导。

**消息会丢吗？**
不会作为「已接收」丢失。服务端保存消息事务与回执，App 断线重连后自动补收；未在 7 天内被接收的消息才会过期。已被接收确认的消息按你的保留策略决定保留多久。

**服务端数据存在哪？怎么备份？**
Go 端只有一个 SQLite 文件，在 `./beacon-data` 里；停容器后直接拷贝整个目录即可，也可以在线用 SQLite 的备份命令。Workers 端数据在 Durable Object 的 SQLite 里，由 Cloudflare 负责持久化。

**能同时用两个服务端吗？**
App 一次只连一个服务器地址。Go 端和 Workers 端协议互通，可以随时改地址切换，但两边数据是独立的，目前没有迁移工具。

**管理 token 忘了怎么办？**
它就是 `docker-compose.yaml` 里的 `BEACON_WEB_ADMIN_TOKEN`（或 Workers 的 `ADMIN_TOKEN` secret），改掉后重启即可，不需要动设备。留空表示关闭管理 API——设备照常收消息，只是无法再通过管理页操作。

**优先级怎么用？**
`0` 只入库不提醒，适合纯记录；`1–3` 静默，不发声不震动；`4–7` 普通通知；`8–10` 重要，会走单独的高优先级通知渠道。默认 `5`。

**接口能自己对接吗？**
可以。所有接口都在 `/api/v1` 下：`capabilities`（能力与版本）、`bind`（一次性绑定码）、`push`（发送）、`messages`（分页拉取）、`messages/ack`（回执）、`receipts/status`（回执查询）、`realtime/config` 与 `realtime/stream`（长连接）、`device/revoke`（自助吊销）。

**能只用 Workers 不买服务器吗？**
可以，这也是 Workers 后端存在的意义：没有机器、没有域名也能跑通，Workers 免费额度对个人推送场景绰绰有余。
