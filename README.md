# Beacon

自托管的 Android 消息推送客户端。部署 Go 或 Cloudflare Workers 服务端后，脚本、CI/CD、NAS 等通过HTTP发送通知；手机通过WebSocket即时接收，或HTTP定时补收，不依赖第三方手机推送通道。

[下载安装](https://github.com/ZeroHomers0/beacon/releases) · [API 文档](API.md) · [MCP 接入](mcp/README.md) · [前端规范](FRONTEND_GUIDELINES.md) · [后端规范](BACKEND_GUIDELINES.md) · [目录规范](REPOSITORY_GUIDELINES.md)

## 能做什么

- 普通事件推送：Markdown正文、优先级、分组、有效期、HTTPS操作按钮，兼容Bark文本推送及手机端AES解密。
- 可靠接收：离线补收、客户端接收确认、发送幂等重试、免打扰、锁屏预览、消息搜索和本地历史备份。
- 灵活接收：即时、省电和规则模式；支持批量接收、自适应心跳及按电量、时间、充电、Wi-Fi切换。
- 手机端管理：设备绑定、应用令牌、主题目录、存储与清理，管理会话独立加密保存。
- 数据主题与桌面组件：持续更新进度、数值、趋势、榜单和整段文字；多个组件独立选择内容、颜色与样式。
- 自动化接入：本地MCP工具及独立查询脚本；业务采集和凭据留在脚本运行机器上。

服务端接受成功表示消息已保存，客户端确认表示已经接收，均不表示用户已读。即时接收受Android后台限制和网络影响；省电接收可能延迟。

## 快速开始

1. 安装Android App，并任选一种服务端部署；无需同时部署Go与Workers。
2. 在App「服务器管理」登录部署时设置的管理令牌，创建一次性绑定码。
3. 在「服务器配置」填写相同服务地址、设备名称及绑定码，或使用管理页面的绑定当前手机入口。
4. 开启通知权限，选择接收模式；需要即时通知时按系统提示允许后台运行。
5. 在连接设置复制推送密钥和请求示例，按 [API 文档](API.md#beacon-原生接口)发送第一条消息。

管理令牌、一次性绑定码、推送密钥和设备接收凭据各有用途，不能互换；应用令牌用于隔离不同脚本来源。详情见 [认证说明](API.md#认证与通用约定)。

## 安装与部署

### Android 客户端

1. 打开 [Releases](https://github.com/ZeroHomers0/beacon/releases)，下载通用包 `beacon-release.apk`。同时提供 `beacon-release-arm64-v8a.apk`（64 位 ARM）和 `beacon-release-armeabi-v7a.apk`（32 位 ARM）；不确定设备架构时选择通用包。
2. 在 Android 8.0 及以上设备安装，首次安装时按系统提示允许对应来源安装应用。
3. 安装后按下文快速开始绑定自己的服务端，并开启通知权限。

若某次构建只有 `beacon-release-unsigned.apk`，该文件需要自行签名后才能安装。自行编译可使用 `android/` 中的 Gradle 工程；正式版本签名配置见 [构建脚本](android/app/build.gradle.kts)。

Android 流水线按 v7a、v8a、通用包三个并行任务构建，分别提供校验和，使用相同的版本号和正式签名。App 内更新继续下载通用包。自行编译时默认生成通用包，也可在 `android/` 下运行 `./gradlew -PbeaconAbi=arm64-v8a :app:assembleRelease` 或 `./gradlew -PbeaconAbi=armeabi-v7a :app:assembleRelease` 生成对应架构的包。

### Go 服务端

#### Docker / NAS 部署

从 [Releases](https://github.com/ZeroHomers0/beacon/releases) 下载 `docker-compose.yaml`，放到单独目录中。也可以使用仓库中的 [Compose 配置](server/docker-compose.yaml)。以下命令适用于 Linux 服务器：

```bash
mkdir -p beacon-data
sudo chown 65532:65532 beacon-data
sudo chmod 700 beacon-data
openssl rand -hex 32
```

将生成的随机值填入配置中的 `BEACON_WEB_ADMIN_TOKEN`，替换示例令牌。然后启动：

```bash
docker compose up -d
docker compose ps
curl http://127.0.0.1:8088/healthz
```

默认镜像为 `ghcr.io/zerohomers0/beacon-server:latest`。数据保存在当前目录的 `beacon-data/`，容器以 UID/GID `65532:65532` 运行，目录必须提前创建并允许该用户写入；NAS 可通过其权限管理界面设置。

配置自己的 HTTPS 域名，例如 `https://push.example.com`，将反向代理上游指向 `http://127.0.0.1:8088`，并开启 WebSocket 转发（保留 `Upgrade` 和 `Connection` 请求头）。Compose 默认只监听宿主机回环地址；如果反向代理运行在其他容器或其他机器，需要相应调整网络与上游地址。

#### 单文件部署

从 [Releases](https://github.com/ZeroHomers0/beacon/releases) 下载符合服务器架构的 `beacon-server-linux-amd64` 或 `beacon-server-linux-arm64`。以 amd64 为例：

```bash
chmod +x beacon-server-linux-amd64
export BEACON_WEB_ADMIN_TOKEN="$(openssl rand -hex 32)"
./beacon-server-linux-amd64 -listen 127.0.0.1:8088 -db ./beacon.db
```

妥善保存生成的管理令牌，以便在 App 中登录服务器管理。正式运行可用 systemd 等方式托管进程，并配置 HTTPS 和 WebSocket 反向代理。数据库会自动初始化。

### Cloudflare Workers 服务端

#### 可视化快速部署

1. 将 [Beacon 仓库](https://github.com/ZeroHomers0/beacon) Fork 到自己的 GitHub 账号。
2. 打开 [Cloudflare 控制台](https://dash.cloudflare.com/)，进入 **Workers & Pages**，创建 Worker，选择通过 GitHub 仓库导入，授权并选中自己的 Fork。
3. 设置根目录为 `workers`，Worker 名称与 `workers/wrangler.toml` 中的 `name` 保持一致，默认是 `beacon-server`。
4. 构建命令填写 `npm run build`，部署命令填写 `npm run deploy`，完成部署。构建依赖来自 `workers/package.json`；若构建环境未自动安装依赖，可将构建命令改为 `npm ci && npm run build`。根目录设置说明见 [Cloudflare 构建配置](https://developers.cloudflare.com/workers/ci-cd/builds/configuration/)。
5. 在该 Worker 的 **Settings → Variables and Secrets（设置 → 变量和机密）** 中添加运行时 **Secret**：名称为 `ADMIN_TOKEN`，值为自己生成并保存的随机令牌。建议使用 `openssl rand -hex 32` 生成的 64 位十六进制字符串。保存并部署使其生效。
6. 访问 `https://你的Worker域名/healthz`，确认返回 `status: ok`，再用该 HTTPS 服务地址在 App 中绑定。

`ADMIN_TOKEN` 必须是运行时机密，放在 Build variables 中无法供服务端使用。未配置或长度不足 32 字符时，服务端返回 `503 server_not_configured`。

本项目通过 [wrangler.toml](workers/wrangler.toml) 声明 `BEACON` Durable Objects 绑定和 SQLite 存储类，首次访问时自动初始化数据库，无需手工创建 D1、KV、R2 或上传 SQL。保留默认 `BEACON_INSTANCE=default`；修改它会指向另一个数据实例，因此绑定设备后应保持不变。

如果默认 `workers.dev` 域名在你的网络中无法访问，可在 Worker 的域名设置中添加自定义域名。SQLite Durable Objects 可用于 Workers 免费计划，具体额度和收费以 [Cloudflare 官方说明](https://developers.cloudflare.com/durable-objects/platform/pricing/) 为准。

#### CLI 部署

本机准备好 Git、Node.js 和 npm 后执行：

```bash
git clone https://github.com/ZeroHomers0/beacon.git
cd beacon/workers
npm ci
npx wrangler login
npm run check
npm run deploy
npx wrangler secret put ADMIN_TOKEN
```

最后一步按提示输入自己生成的管理令牌。部署完成后用 `/healthz` 检查服务，并保存服务地址与管理令牌。

## 数据主题与小组件

普通消息记录一次事件，数据主题保存一组内容的最新状态。先在「服务器管理 → 数据主题」预创建主题，再把标识和上报示例交给脚本。主题属于设备，关联应用可选；推送凭据只更新已有主题，不能创建主题。

长按桌面空白处，从系统小组件选择器添加Beacon；App的「小组件管理」只管理已有桌面实例。支持 **2×2、3×2、4×2、4×4**，每个实例独立绑定主题，按桌面返回的实际尺寸预览。系统选择器使用各规格独立示例图。

| 内容 | 展示样式 |
| --- | --- |
| 数值、进度 | 纯数字、横条、柱图、单环、嵌套环、独立小环 |
| 组成与趋势 | 占比圆环、迷你趋势图 |
| 榜单与公告 | 文字列表、整段文字 |

尚未上报的主题也能提前绑定，显示“等待数据”，收到数据后自动匹配；手动选择的样式不会被后台覆盖。未绑定指引完整换行，不使用省略号。保存后停留当前设置页，无修改时保存按钮灰显。组件统一圆角，字号和粗细受安全档位约束。

编辑页按内容、显示样式、颜色与背景、更新与提示组织。“恢复默认外观”仅重置颜色、背景、字号和图形粗细，保留绑定、内容顺序、模板和更新规则；确认后先预览，点击保存才写入。

清空数据保留主题和版本水位；删除主题直接移除目录与数据，脚本无法自动重建。完整字段、预创建流程、各类数据块示例与小组件发送方式统一放在 [API.md](API.md#实时数据主题与小组件发送-api)。

桌面更新会合并节流：及时约5秒，均衡下任务约5秒、其他约15秒，省电约1分钟；不保证逐秒或每帧动画。小米桌面实际网格与后台行为仍需实机验证，旧大号实例若仍占5行可移除后重新添加。

## 接入示例与日常使用

- [MCP](mcp/README.md)：本机stdio服务，支持AI客户端发送通知及只读查询能力、管理概况。
- [青龙仓库订阅](examples/qinglong/README.md)：直接订阅本仓库，按白名单自动导入新闻、热搜和额度任务；仅依赖Python标准库。
- [青龙热搜榜](examples/qinglong/hot-search/README.md)：每半小时抓取60s热搜榜写入数据主题，用文字列表小组件展示。
- [青龙Codex额度](examples/qinglong/codex-quota/README.md)：每5分钟查询Codex五小时与周额度，变化时上报、最长30分钟保鲜，以四项数值展示剩余额度与重置时间。
- [外部Webhook适配](examples/webhooks/README.md)：Python标准库接收GitHub/Grafana事件，完成验签、固定映射和有界幂等重试，不内置第三方业务到服务端。
- [结构化内容示例](examples/structured/README.md)：外部任务采集并发送进度等数据；新主题需先预创建。
- 接收模式默认省电；即时模式保持WebSocket，规则模式按环境切换。普通消息批量接收会增加优先级0–7的等待时间，8–10仍即时投递。
- 消息详情支持Markdown、查看原文、复制和分享；图片默认不下载，链接与操作按钮由用户点击后打开。
- 查看历史时，新消息到达显示“有 N 条新消息 · 查看”，保持阅读位置；点击后加载最新列表并回到顶部。数量遵循当前搜索和筛选，与未读数分开。
- 通知设置可临时静音全部提醒；消息详情菜单可静音该发送应用，数据主题详情可只静音该主题。支持1小时、8小时、到明早8点或恢复提醒，选择后立即生效。消息仍保存和确认、组件继续更新，重要通知也会静音；恢复后不补响旧提醒。各范围独立，恢复某一范围不会解除其他范围的静音。
- 接收设置沿用现有连接诊断，显示已安排的下次重试时间；不会为显示倒计时新增后台轮询。服务器管理员可通过[运维统计接口](API.md#运维统计快照)查询积压、重试和就绪状态。
- 手机本地历史、服务端保留期和数据主题TTL分别管理；设置备份不应携带接收凭据或管理令牌。

## 常见问题

**服务地址没有管理网页？** 当前服务端提供API，管理入口在Android App。通过健康检查确认服务可达。

**API成功但没通知？** 先确认App中是否有消息，再检查通知权限、渠道、临时静音、免打扰、优先级、接收模式和后台权限。即时模式还需要反向代理转发WebSocket，HTTP空闲超时应允许至少450秒。

**Workers提示未配置？** 设置运行时Secret `ADMIN_TOKEN`，至少32字符，并保留稳定的 `BEACON_INSTANCE`；构建变量不能代替运行时机密。

**消息保存多久？** 未确认消息投递窗口7天，回执窗口30天；已确认历史默认1天，可通过Go `BEACON_HISTORY_RETENTION_DAYS` 或Workers `HISTORY_RETENTION_DAYS` 调为1–30天。手机本地历史独立管理。额度及TTL详见 [API 文档](API.md)。

**可以用HTTP吗？** 正式使用HTTPS。外部脚本应通过请求头传递令牌，避免在日志和共享链接中泄露凭据；调试例外以客户端地址校验为准。

**密钥泄露怎么办？** 撤销或轮换相应设备/应用凭据。主题标识不是鉴权凭据；禁止自动创建不能阻止泄露密钥更新其权限范围内的已有主题。

## 开发与仓库约定

| 目录 | 职责 |
| --- | --- |
| `android/` | Kotlin / Compose客户端、双UI风格与桌面组件 |
| `server/` | Go / Gin / SQLite服务端 |
| `workers/` | TypeScript / Durable Objects SQLite服务端 |
| `contract/` | 两种后端共用的协议与边界样例 |
| `mcp/` | 本地MCP服务 |
| `examples/` | 外部接入脚本与说明 |
| `tools/`、`.github/` | 可复用校验、诊断工具与CI |

修改前阅读 [前端规范](FRONTEND_GUIDELINES.md)、[后端规范](BACKEND_GUIDELINES.md)和 [目录规范](REPOSITORY_GUIDELINES.md)。AI指引位于 [AGENTS.md](AGENTS.md)与 [CLAUDE.md](CLAUDE.md)。规范对应现有实现，变更公共组件、协议或目录时须同步更新。

```bash
node tools/check-repository.mjs
chmod +x .githooks/pre-commit
git config core.hooksPath .githooks
```

目录检查同时用于提交前钩子和CI。`artifacts/`、`docs/design/`、临时目录、构建产物、运行数据库及真实凭据不进入仓库；测试源码、脱敏协议样例及正式UI资源应提交。完整构建和验证命令见对应开发规范。

## 开源许可与第三方致谢

Beacon 项目本身以 [MIT License](LICENSE) 发布。

第三方组件及图标的许可说明统一在此查阅，App 的“使用与部署文档”入口可打开本 README。

| 组件或资源 | 版权归属 | 许可 |
| --- | --- | --- |
| OkHttp、Okio | Square, Inc. | Apache-2.0 |
| Material Symbols Rounded | Google LLC | Apache-2.0 |
| MIUIX | compose-miuix-ui contributors | Apache-2.0 |
| Markwon core | Markwon contributors | Apache-2.0 |
| commonmark-java | Atlassian Pty Ltd | BSD-2-Clause |

完整归属信息见 [第三方 NOTICE](android/app/src/main/assets/third-party/NOTICE.txt)，许可全文见 [Apache-2.0](android/app/src/main/assets/third-party/Apache-2.0.txt) 和 [BSD-2-Clause](android/app/src/main/assets/third-party/commonmark-BSD-2-Clause.txt)。这些声明与许可文件仍随 APK 分发。上述第三方许可与 [MIT License](LICENSE)（Beacon 项目自身）互相独立，各覆盖各自的部分。
