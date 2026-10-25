# Codex 额度 → Beacon 数据主题（青龙面板定时任务）

推荐使用[统一青龙订阅配置](../README.md#订阅配置)，自动导入本任务及默认定时。下面的 `docker cp` 和手工命令适用于不使用订阅的独立部署，订阅任务应保留面板生成的仓库路径前缀。

每半小时读一次 Codex 的两条额度：**五小时窗口**和**七天窗口**，作为完整快照写进 Beacon 的**数据主题**，手机桌面小组件直接显示「还剩多少、什么时候重置」，不弹通知。

- 两条进度条按**剩余百分比**展示，剩余越少越应标红（默认已用 70% 黄、90% 红，可调）
- 明细列表给出套餐类型、两个窗口的重置时间、额外额度和重置次数
- 附两个倒计时块，在 App 详情页显示「还有多久重置」（桌面暂无倒计时模板，桌面看进度条即可）
- 只用 Python 标准库，青龙镜像无需 `pip install`
- `access_token` 快过期时用 `refresh_token` 自动续期，续期结果回写 `auth.json` 与本地缓存
- 版本号读服务端水位后自增，本地存水位文件，重启不会撞 `409`

## 1. 脚本

[codex_quota_to_beacon.py](codex_quota_to_beacon.py)

## 2. 先在 App 里创建主题

Beacon 的数据主题必须预创建，**推送密钥不能创建主题**，脚本跑到未登记的主题会返回 404。打开 App「服务器管理 → 数据主题」新建一个：

| 字段 | 值 |
| --- | --- |
| 主题标识 | `widget/codex-quota` |
| 名称 | Codex 额度 |

新建对话框里选设备、填名称和主题标识即可，不需要创建应用：未关联应用时脚本用设备推送密钥上报。主题标识要和脚本的 `TOPIC_KEY` 一致，想用别的标识就两边一起改。

创建后可先用 `DRY_RUN=1` 验证额度读取和请求内容（见下面「手工验证」）；此模式不查询Beacon版本号、不提交数据。首次真实运行会查询主题的版本水位。**主题不存在时脚本只会报错退出，不会自己创建**。

## 3. 取出 Codex 凭据

脚本支持两种普通 Codex OAuth 凭据，`CODEX_AUTH_JSON` 均可填写文件路径或整段单账号 JSON：

- Codex CLI 原生 `auth.json`：令牌位于 `tokens.access_token`、`tokens.refresh_token`。
- CLIProxyAPI 导出的 Codex 认证文件：`type` 为 `codex`，令牌位于顶层 `access_token`、`refresh_token`。格式依据 [CLIProxyAPI 源码](https://github.com/router-for-me/CLIProxyAPI/blob/main/internal/auth/codex/token.go)。

CLIProxyAPI 导出示意（以下均为占位值）：

```json
{"type":"codex","access_token":"<access-token>","refresh_token":"<refresh-token>","account_id":"<account-id>"}
```

直接把原导出 JSON 交给脚本即可，不必手工包一层 `tokens`，也不必为适配格式重新执行 `codex login`。脚本自动读取账号 ID；`CODEX_ACCOUNT_ID` 仅用于手动覆盖。刷新时保持原来的顶层/嵌套结构，并保留CLIProxyAPI的其他元数据。旧版没有账号标记的令牌缓存不会复用，避免更换账号后读取上一账号的额度。

需要导出的是Codex账号的认证JSON，不是CLIProxyAPI对外提供的API Key，也不是多账号ZIP/数组。当前不支持启用`use_siwc`的独立凭据分区。如果同一份登录凭据仍由CLIProxyAPI维护续期，可设置`CODEX_REFRESH=0`并更新导出文件，避免两个程序同时轮换同一refresh token。

Codex CLI 原生 `auth.json` 默认位置：

| 系统 | 路径 |
| --- | --- |
| Linux / macOS | `~/.codex/auth.json` |
| Windows | `%USERPROFILE%\.codex\auth.json` |

青龙是跑在容器里的，所以要把这个文件带进去，两种办法：

**方式 A：挂载（推荐）**

把 `auth.json` 放到宿主机某个目录，在青龙容器的启动参数里加一行挂载：

```bash
-v /path/to/auth.json:/ql/data/auth.json:rw
```

脚本用 `CODEX_AUTH_JSON=/ql/data/auth.json` 即可。挂载成 `:rw` 才能让续期结果写回去；`auth.json` 是只读时脚本会退化成只写本地缓存，仍能跑，只是容器重建后要重新准备凭据。

**方式 B：直接把内容塞进环境变量**

`CODEX_AUTH_JSON` 除了路径，也接受**整段 JSON**。下面是原生格式示例，CLIProxyAPI 格式见上文：

```json
{"auth_mode":"chatgpt","tokens":{"access_token":"...","refresh_token":"...","account_id":"..."},"last_refresh":"..."}
```

好处是不用挂文件，代价是青龙面板里存了一份含令牌的明文。**两种方式都要注意：`auth.json` 里有账号令牌，等同于账号密码，别提交到任何仓库、别贴进聊天工具。**

> 容器里没有 `~/.codex`，也没有 `codex` 命令，脚本不需要它们：它直接请求额度接口，不调用 Codex CLI。

## 4. 部署到青龙

```bash
docker cp codex_quota_to_beacon.py qinglong:/ql/data/scripts/codex-quota/codex_quota_to_beacon.py
```

青龙面板里新建「定时任务」：

| 字段 | 值 |
| --- | --- |
| 名称 | Codex 额度 |
| 命令 | `task codex-quota/codex_quota_to_beacon.py` |
| 定时规则 | `*/30 * * * *` |

`*/30 * * * *` 表示每小时的 0 分和 30 分各跑一次。时区为容器时区，青龙官方镜像默认 `Asia/Shanghai`。

也可以把脚本提交到自己的脚本仓库，在青龙「订阅管理」里指向该仓库（白名单填 `codex_quota_to_beacon.py`），定时任务命令不变。

## 5. 环境变量

在青龙「环境变量」页面添加：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `BEACON_BASE_URL` | 是 | Beacon 服务地址，如 `https://push.gzautobot.top` |
| `BEACON_PUSH_KEY` | 是 | 推送密钥。App →「服务器管理」→ 连接设置 → 复制推送密钥 |
| `CODEX_AUTH_JSON` | 是 | `auth.json` 的路径，或整段 JSON 内容 |
| `CODEX_TOKEN_CACHE` | 否 | 续期后的令牌缓存，默认脚本同目录 `.codex_quota_token.json` |
| `CODEX_REFRESH` | 否 | 是否允许自动续期，默认开启；设 `0` 只读不写 |
| `TOPIC_KEY` | 否 | 主题标识，默认 `widget/codex-quota` |
| `FRESH_FOR_SECONDS` | 否 | 多旧算过期（秒），默认 `3600`；`0` 表示不标过期 |
| `WARN_PERCENT` | 否 | 已用比例到多少标黄，默认 `70` |
| `CRITICAL_PERCENT` | 否 | 已用比例到多少标红，默认 `90` |
| `BEACON_TTL` | 否 | 数据有效期（秒），默认 `604800`；`0` 用服务端默认 |
| `BEACON_GROUP` | 否 | 消息分组，默认 `codex` |
| `BEACON_PRIORITY` | 否 | 优先级 `0`–`10`，默认 `0`（只刷新桌面，不弹通知） |
| `STATE_FILE` | 否 | 版本水位文件路径，默认脚本同目录 `.codex_quota_state.json` |
| `LOCK_FILE` | 否 | 运行锁路径，默认脚本同目录 `.codex_quota.lock` |
| `DRY_RUN` | 否 | `1` 只打印将要发送的 JSON，不实际上报（此时可以不填推送密钥） |

`CODEX_USAGE_URL`、`CODEX_TOKEN_URL`、`CODEX_CLIENT_ID`、`CODEX_ACCOUNT_ID` 是给接口变更或多账号场景留的覆盖项，一般不用动。

`fresh_for_seconds` 默认 3600，正好是采集间隔的两倍：脚本停摆超过一小时，桌面上会明确提示数据旧了，而不是继续展示过期的剩余额度。它不能超过 `BEACON_TTL`，脚本会自动取两者的较小值。

## 6. 手工验证

```bash
# 只渲染、不推送，确认凭据能读到、额度能拉到
docker exec -e BEACON_BASE_URL=https://push.gzautobot.top -e DRY_RUN=1 qinglong \
  python3 /ql/data/scripts/codex-quota/codex_quota_to_beacon.py

# 真推一轮
docker exec -e BEACON_BASE_URL=https://push.gzautobot.top -e BEACON_PUSH_KEY=你的密钥 qinglong \
  python3 /ql/data/scripts/codex-quota/codex_quota_to_beacon.py
```

跑完在 App「服务器管理 → 数据主题」里能看到该主题的版本号和新内容。

长按桌面添加 Beacon 小组件 → 选择主题 → 样式保持「自动」即可：桌面模板只认 `progress`/`metric`/`list`/`sparkline`/`segments` 这几类块，自动样式会选中「横条」，并默认绑定主题里的两条 `progress`（5 小时与周）。

想在桌面上按剩余额度变色，进入小组件设置 →「颜色与背景」→ 打开「显示分段提示颜色」、「内容配色」选「按状态变色」。手选固定颜色会覆盖分段范围（协议里 `bands` 只在没有手选颜色时生效）。倒计时块和 `alert` 只出现在 App 的主题详情页，桌面模板不渲染它们。

## 7. 行为说明

- **上报表单**：每次推送是该主题的**完整快照**，不是增量。块组成：两个 `progress`（5 小时 / 周）+ 两个 `countdown`（重置倒计时）+ 一个 `list`（明细），额度用尽时追加一个 `alert`，最多 6 块。
- **按剩余展示**：`value` 是**还剩多少**（`100 - used_percent`）。这样「条越满越安全」，和小组件其他存储/流量类主题的读法一致。颜色由 `bands` 反向配置：剩余 10% 以下红、30% 以下黄、其余正常。
- **容错**：接口只返回一个窗口时（例如没有周额度）只画那一条；`reset_at` 缺失时不生成倒计时块，也不会在正文里编一个重置时间。
- **自动续期**：解出 JWT 的 `exp`，离过期不到 5 分钟就先用 `refresh_token` 换新令牌，再请求额度。额度接口返回 401/403 时也会刷新一次重试。刷新失败直接退出码 2 并提示重新 `codex login`。
- **令牌落盘**：新令牌先回写 `auth.json`（保证宿主机上的 Codex CLI 也拿到新令牌），再写脚本同目录的缓存。`auth.json` 只读时只告警，继续用缓存跑。
- **版本**：每次推送先 `GET /api/v1/states/meta` 读服务端水位，和本地水位取较大值再 +1。遇到 `409` 会重读一次水位重新提交。**同一个主题不要用多个设备/脚本同时写。**
- **补发**：发送前先把待发请求写进水位文件，成功后清掉；上次异常退出留下的请求会在下一轮先补发，避免版本号跳空。
- **并发保护**：用 `.codex_quota.lock` 独占运行，两个实例撞上时后一个直接跳过（退出码 0）；进程被强杀留下的锁超过 10 分钟自动接管。
- **退出码**：`0` 成功或已跳过，`1` 配置错误（含主题未创建、凭据读不到），`2` 额度拉取或令牌续期失败，`3` 上报失败。青龙按非 0 标记任务失败，方便配通知。
- **重试**：网络错误与 5xx 最多重试 2 次（间隔 2s、4s）；429 按 `Retry-After` 等待；4xx 不重试。
- **频率**：每半小时一次写入，远低于服务端「每应用每设备每分钟 120 次」的限制。

## 8. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| `数据主题 widget/codex-quota 还没创建` | 主题没在 App 里建，或标识拼错；推送密钥不能创建主题 |
| `读不到 Codex 凭据` | `CODEX_AUTH_JSON` 路径在容器里不存在、文件权限不足，或 JSON 解析失败（Windows 记事本存的 BOM 已兼容） |
| `凭据里没有 tokens.access_token` | `auth.json` 内容不完整，重新在本机 `codex login` 后复制整个文件 |
| `刷新令牌失败：HTTP 401 ... token_expired` | `refresh_token` 已被吊销（换过账号、在别处登出）；重新 `codex login` 并更新凭据 |
| `额度接口返回 HTTP 401` | 令牌失效且续期也失败，同上处理 |
| `额度接口没有返回五小时/七天窗口数据` | 账号当前套餐没有这两个窗口（例如纯 API 计费账号）；本脚本只适用于 ChatGPT 登录的 Codex |
| `HTTP 409` | 版本冲突，通常是另一个写入方也在写同一主题；脚本会自动重试一次 |
| 桌面显示「数据已过期」 | 超过 `FRESH_FOR_SECONDS` 没更新，检查定时任务是否在跑 |
| 桌面显示「已过有效期」 | 超过 `BEACON_TTL`，数据被服务端移除 |
| 手机没有立刻刷新 | 服务端返回成功只代表数据已落库，桌面刷新被系统合并节流，省电模式下约 1 分钟 |

本地运行状态文件（`.codex_quota_state.json`、`.codex_quota_token.json`、`.codex_quota.lock`）属于运行数据，已被仓库忽略，不要提交。

## 离线兼容验证

```bash
python -m unittest discover -s examples/qinglong/codex-quota -v
```

测试只使用合成凭据与模拟接口，不执行真实登录、刷新或推送。
