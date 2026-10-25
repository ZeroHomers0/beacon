# Beacon 青龙订阅脚本

使用现有 [Beacon 仓库](https://github.com/ZeroHomers0/beacon) 即可订阅；Git 仓库地址不能填写 GitHub 的 `tree/main/examples/qinglong` 目录页面。通过路径白名单仅将本目录的 Python 任务导入青龙，不会把 Android、后端和其他示例加入定时任务。Git 首次克隆仍会获取整个仓库源码。

## 订阅配置

在青龙「订阅管理 → 新建订阅」填写：

| 字段 | 值 |
| --- | --- |
| 名称 / 别名 | `Beacon` / `beacon`（支持别名的版本可填写） |
| 类型 | 公开仓库 |
| 链接 | `https://github.com/ZeroHomers0/beacon.git` |
| 分支 | `main` |
| 白名单 | `examples/qinglong/.*_to_beacon\.py$` |
| 黑名单 | `test_\|__pycache__` |
| 依赖文件 | 留空，三个脚本均只依赖 Python 标准库 |
| 文件后缀 / 扩展名 | `py` |
| 自动添加任务 | 开启 |
| 订阅更新定时 | `0 6 * * *`，每天更新一次脚本 |

保存后点一次「运行」拉取仓库，青龙会读取脚本头部的任务名称和 cron 标记并生成任务。先配置下方环境变量，再启用/运行具体任务；无需三个任务全部启用。订阅更新的 cron 只负责拉取代码，不代表消息推送频率。已有任务的时间通常不会被重复导入覆盖，需要在「定时任务」中手动调整。

也可在青龙容器终端运行（需要面板已开启自动添加任务）：

```bash
ql repo "https://github.com/ZeroHomers0/beacon.git" "examples/qinglong/.*_to_beacon\.py$" "test_|__pycache__" "" "main" "py"
```

导入后的任务命令包含仓库目录前缀，例如 `task beacon/examples/qinglong/60s_to_beacon.py`；实际前缀取决于青龙版本和订阅别名，以面板自动生成的命令为准，不要改成缺少仓库前缀的手工复制命令。

## 可选任务

| 任务 | 默认定时 | 配置说明 |
| --- | --- | --- |
| 每日新闻 | `30 7 * * *`，每天07:30 | [下方每日新闻配置](#每日新闻详细配置)，普通消息，无需数据主题 |
| 热搜榜 | `*/30 * * * *`，每30分钟 | [热搜榜说明](hot-search/README.md)，先创建对应数据主题 |
| Codex 额度 | `*/5 * * * *`，每5分钟查询，变化时上报、最长30分钟保鲜 | [额度说明](codex-quota/README.md)，先创建主题并准备自身凭据 |

公共变量为 `BEACON_BASE_URL` 和 `BEACON_PUSH_KEY`；热搜榜额外设置 `HOT_SOURCES`，额度任务额外设置 `CODEX_AUTH_JSON`。每个脚本的可选项见各自说明。不要把用于单个任务的 `STATE_FILE`、`TOPIC_KEY`、`DRY_RUN` 等覆盖项全局套到所有任务；按青龙版本使用任务级变量或在单个任务命令前设置，仅对该任务生效。

订阅只同步代码，不会导入密钥或代建主题。凭据和运行日志/缓存留在青龙持久化数据目录；额度凭据建议放在订阅目录之外，例如 `/ql/data/auth.json`，以免重建订阅目录时丢失。青龙删除订阅并清理文件前，应自行保存运行状态和凭据；不要同时保留手动复制与订阅生成的同一任务，以免重复执行。

配置字段依据 [青龙官方仓库](https://github.com/whyour/qinglong) 的订阅过滤与任务元数据实现，界面字段名称可能随版本变化。

## 每日新闻详细配置

每天定时拉取 [60s API](https://docs.60s-api.viki.moe/) 的「每天 60 秒读懂世界」，整理成一条 Markdown 消息推送到 [Beacon](../..)。

- 只用 Python 标准库，青龙镜像无需 `pip install`
- 纯 HTTP 实现（`urllib`），服务器没有 Node.js 也能跑
- 次日自动去重：同一天只推一次，配合 `Idempotency-Key` 让服务端也认得出重复
- 拉取和推送都带重试；429 会读 `Retry-After` 再重试

## 1. 脚本

[60s_to_beacon.py](60s_to_beacon.py)

## 2. 部署到青龙

### 方式 A：直接放脚本（最简单）

```bash
docker cp 60s_to_beacon.py qinglong:/ql/data/scripts/60s_to_beacon.py
```

然后在青龙面板「定时任务」里新建：

| 字段 | 值 |
| --- | --- |
| 名称 | 60s 新闻 |
| 命令 | `task 60s_to_beacon.py` |
| 定时规则 | `30 7 * * *` |

定时规则是标准 5 段 cron（分 时 日 月 周），时区为容器时区，青龙官方镜像默认 `Asia/Shanghai`。

### 方式 B：放仓库里用订阅

按上方[订阅配置](#订阅配置)填写现有 Beacon 仓库即可，无需另建仓库。若仅需新闻，将白名单改为 `examples/qinglong/60s_to_beacon\.py$`。命令使用面板自动生成的含仓库前缀版本。

## 3. 环境变量

在青龙「环境变量」页面添加（值 `$` 开头的变量名青龙默认不注入任务，注意别被面板的变量名规则拦住）：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `BEACON_BASE_URL` | 是 | Beacon 服务地址，如 `https://push.gzautobot.top` |
| `BEACON_PUSH_KEY` | 是 | 设备推送密钥。App →「服务器管理」→ 连接设置 → 复制推送密钥 |
| `SIXTY_API_BASE` | 否 | 60s API 地址，默认 `https://60s.gzautobot.top` |
| `BEACON_GROUP` | 否 | 消息分组，默认 `60s`；手机上可按分组筛选 |
| `BEACON_PRIORITY` | 否 | 优先级 0–10，默认 `5`（普通通知）。`0–1` 只存不响，`2–3` 静默，`8–10` 重要 |
| `BEACON_TTL` | 否 | 投递有效期（秒），默认 `86400`；`0` 用服务端默认七天 |
| `NEWS_LIMIT` | 否 | 只推前 N 条，`0` = 全部（默认） |
| `INCLUDE_IMAGE` | 否 | `1` 时正文附带当日长图（默认关闭，图在手机端需点击加载） |
| `INCLUDE_BUTTON` | 否 | `1`/`0`，是否带「看原文」按钮，默认开启 |
| `FORCE_PUSH` | 否 | `1` 忽略本地去重，强制重推 |
| `DRY_RUN` | 否 | `1` 只打印将要发送的 JSON，不实际推送 |
| `STATE_FILE` | 否 | 去重状态文件路径，默认脚本同目录 `.60s_to_beacon_state.json` |

## 4. 手工验证

```bash
# 只渲染、不推送，检查环境和内容
docker exec -e BEACON_BASE_URL=https://push.gzautobot.top -e DRY_RUN=1 qinglong \
  python3 /ql/data/scripts/60s_to_beacon.py

# 真推一条
docker exec -e BEACON_BASE_URL=https://push.gzautobot.top -e BEACON_PUSH_KEY=你的密钥 qinglong \
  python3 /ql/data/scripts/60s_to_beacon.py
```

也可以在青龙任务详情页点「运行」看日志。

## 5. 行为说明

- **去重**：脚本把 `date` 写进状态文件；同一天第二次运行直接跳过（退出码 0），日志会说明原因。删掉状态文件或设 `FORCE_PUSH=1` 可重推。
- **服务端幂等**：推送时带 `Idempotency-Key: 60s-<日期>`，七天内同键同内容不会被服务端重复保存，返回 409 也按成功处理。
- **退出码**：`0` 成功或已跳过，`1` 配置错误，`2` 拉取 60s 失败，`3` 推送失败。青龙按非 0 标记任务失败，方便配通知。
- **重试**：网络错误与 5xx 最多重试 2 次（间隔 2s、4s）；429 按 `Retry-After` 等待。4xx（除 429）不重试。
- **内容格式**：标题用日期，正文为编号列表 + 微语，服务端与 App 都支持 Markdown 渲染。

## 6. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| `缺少环境变量 BEACON_BASE_URL` | 青龙环境变量没加，或任务没勾选「环境变量生效」 |
| `HTTP 401 unauthorized` | 推送密钥无效或已撤销，重新从 App 复制；注意别把管理令牌当成推送密钥 |
| `HTTP 429` | 推送太频繁，脚本会自动退避重试；仍失败可减少运行次数 |
| 拉取失败 `HTTP 5xx` | 自建 60s 实例异常，可换 `SIXTY_API_BASE` 或稍后重跑 |
| 手机没有立刻响 | Beacon 服务端返回成功只代表已排队投递；省电接收模式下由系统调度，可能有分钟级延迟 |
