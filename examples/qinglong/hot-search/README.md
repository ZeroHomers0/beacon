# 热搜榜 → Beacon 数据主题（青龙面板定时任务）

推荐使用[统一青龙订阅配置](../README.md#订阅配置)，自动导入本任务及默认定时。下面的 `docker cp` 和手工命令适用于不使用订阅的独立部署，订阅任务应保留面板生成的仓库路径前缀。

每半小时从 [60s API](https://docs.60s-api.viki.moe/) 拉一批热搜榜，写进 Beacon 的**数据主题**，手机桌面小组件用「文字列表」样式直接显示榜单，不弹通知、不打扰。

- 每个榜单一个主题，互不影响：`hot/weibo`、`hot/baidu`、`hot/zhihu` ……
- 只用 Python 标准库，青龙镜像无需 `pip install`
- 榜单顺序原样保留，桌面端自己加序号和热度，脚本不做二次排序
- 版本号读服务端水位后自增，本地存一份水位文件，重启不会撞 `409`
- 单个榜单拉取失败只跳过它，其余榜单照常更新

## 1. 脚本

[hot_search_to_beacon.py](hot_search_to_beacon.py)

## 2. 先在 App 里创建主题

Beacon 的数据主题必须由管理员预创建，**推送密钥不能自动创建主题**，脚本跑到未登记的主题会返回 404。先打开 App「服务器管理 → 数据主题」按需创建：

| 主题标识 | 内容 | 对应 `HOT_SOURCES` |
| --- | --- | --- |
| `hot/weibo` | 微博热搜 | `weibo` |
| `hot/baidu` | 百度热搜 | `baidu` |
| `hot/zhihu` | 知乎热榜 | `zhihu` |
| `hot/douyin` | 抖音热榜 | `douyin` |
| `hot/toutiao` | 头条热榜 | `toutiao` |
| `hot/rednote` | 小红书热搜 | `rednote` |
| `hot/tieba` | 贴吧热议 | `tieba` |
| `hot/itnews` | IT之家热榜 | `itnews` |
| `hot/hackernews` | Hacker News | `hackernews` |
| `hot/bili` | B站热搜 | `bili` |

默认只推 `weibo,baidu,zhihu` 三个，先建这三个就够。主题名（App 里显示的名字）随你填；当前客户端优先展示预创建主题的名称，脚本中的 `title` 用作数据标题及兼容展示。主题标识使用别的前缀时改 `HOT_TOPIC_PREFIX`，两边要一致。

> 上游榜单接口可能临时返回5xx，脚本重试后会跳过失败榜单，日志中会写明原因。

## 3. 部署到青龙

```bash
docker cp hot_search_to_beacon.py qinglong:/ql/data/scripts/hot-search/hot_search_to_beacon.py
```

青龙面板里新建「定时任务」：

| 字段 | 值 |
| --- | --- |
| 名称 | 热搜榜 |
| 命令 | `task hot-search/hot_search_to_beacon.py` |
| 定时规则 | `*/30 * * * *` |

定时规则是标准 5 段 cron，时区为容器时区，青龙官方镜像默认 `Asia/Shanghai`。`*/30 * * * *` 表示每小时的 0 分和 30 分各跑一次。

也可以把脚本提交到自己的脚本仓库，在青龙「订阅管理」里指向该仓库（白名单填 `hot_search_to_beacon.py`），定时任务命令不变。

## 4. 环境变量

在青龙「环境变量」页面添加：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `BEACON_BASE_URL` | 是 | Beacon 服务地址，如 `https://push.gzautobot.top` |
| `BEACON_PUSH_KEY` | 是 | 推送密钥。App →「服务器管理」→ 连接设置 → 复制推送密钥 |
| `SIXTY_API_BASE` | 否 | 60s API 地址，默认 `https://60s.gzautobot.top` |
| `HOT_SOURCES` | 否 | 逗号分隔的榜单，默认 `weibo,baidu,zhihu`；`all` 表示全部 |
| `HOT_LIMIT` | 否 | 每个榜单取前 N 条，`1`–`12`，默认 `10`。协议上限就是 12 |
| `HOT_TOPIC_PREFIX` | 否 | 主题前缀，默认 `hot`；改成 `widget/hot` 则主题为 `widget/hot/weibo` |
| `HOT_SHOW_HEAT` | 否 | 是否在条目后附热度（`标题 · 111.4万`），默认开启；设 `0` 关闭 |
| `HOT_PRIORITY` | 否 | 优先级 `0`–`10`，默认 `1`（只刷新桌面，不弹通知）。`0`–`1` 只存不响 |
| `HOT_GROUP` | 否 | 消息分组，默认 `hot` |
| `HOT_TTL` | 否 | 数据有效期（秒），默认 `86400`；到期后小组件显示「已过有效期」 |
| `HOT_FRESH_FOR` | 否 | 新鲜期（秒），默认 `3600`；超过后桌面显示「数据已过期」但仍保留最后内容 |
| `HOT_STATE_FILE` | 否 | 版本水位文件路径，默认脚本同目录 `.hot_search_to_beacon_state.json` |
| `DRY_RUN` | 否 | `1` 只打印将要发送的 JSON，不实际推送 |

`fresh_for_seconds` 建议保持默认的 3600（两倍采集间隔）：这样脚本停摆超过一小时，桌面上会明确提示数据旧了，而不是继续展示过期榜单。

## 5. 手工验证

```bash
# 只渲染、不推送，确认能拉到榜单
docker exec -e BEACON_BASE_URL=https://push.gzautobot.top -e DRY_RUN=1 qinglong \
  python3 /ql/data/scripts/hot-search/hot_search_to_beacon.py

# 真推一轮
docker exec -e BEACON_BASE_URL=https://push.gzautobot.top -e BEACON_PUSH_KEY=你的密钥 qinglong \
  python3 /ql/data/scripts/hot-search/hot_search_to_beacon.py
```

跑完在 App「服务器管理 → 数据主题」里能看到对应主题的版本号和新内容。长按桌面添加 Beacon 小组件 → 选择主题 → 样式选「自动」或「文字列表」→ 在「榜单显示」里设置起始排名、最多显示条数、每条行数。

## 6. 行为说明

- **上报表单**：每次推送是该主题的**完整快照**，不是增量。`blocks` 里只有一个 `kind:"list"` 的块，`items` 顺序就是榜单顺序；桌面「文字列表」模板要求恰好一个 list 块，所以不要再往里塞别的块。
- **序号**：桌面渲染时自己加 `1. ` 前缀，脚本写入的 `text` 不带编号。`body` 里另存一份带编号的纯文本，供「整段文字」模板和降级展示。
- **热度**：各榜单字段不统一（微博/抖音/头条是 `hot_value` 整数，百度/贴吧是 `score_desc`，知乎是 `hot_value_desc`，小红书是 `score` 字符串），脚本优先取接口给的现成描述，没有才把整数格式化成「万/亿」。
- **版本**：每次推送先 `GET /api/v1/states/meta` 读服务端水位，和本地水位取较大值再 +1。遇到 `409 stale_state_revision`/`state_revision_conflict` 会重读一次水位重新提交。**单写入者**才好用，同一个主题不要让别的脚本同时写。
- **去重**：榜单变化频繁，因此**不按内容去重**，每轮都推。内容恰好没变时服务端返回 `unchanged:true`，桌面不会闪一下。
- **失败隔离**：某个榜单拉取失败（上游 500、超时）只跳过它；某主题未创建只报这一条。别的榜单照常更新。
- **退出码**：`0` 全部成功，`1` 配置错误，`2` 一个榜单都没拉到，`3` 有主题推送失败。青龙按非 0 标记任务失败，方便配通知。
- **重试**：网络错误与 5xx 最多重试 2 次（间隔 2s、4s）；429 按 `Retry-After` 等待；4xx 不重试。
- **频率**：每半小时每个主题一次写入，3 个主题就是 6 次/半小时，远低于服务端「每应用每设备每分钟 120 次」的限制。

## 7. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| `主题 hot/xxx 未登记` | 主题没在 App 里创建，或创建的设备/应用和推送密钥不匹配。推送密钥不能创建主题 |
| `HTTP 401 unauthorized` | 推送密钥无效或已撤销，重新从 App 复制；注意别把管理令牌当推送密钥 |
| `HTTP 409 topic_deleted` | 主题被管理员删了，重新创建即可（新建属于新一轮数据，revision 从 1 开始） |
| 桌面显示「等待数据」 | 主题已建好但还没推送成功，看青龙任务日志确认推送结果 |
| 桌面显示「数据已过期」 | 超过 `HOT_FRESH_FOR` 没更新，检查定时任务是否在跑 |
| 桌面显示「已过有效期」 | 超过 `HOT_TTL`，数据被服务端移除了 |
| 某个榜单一直跳过 | 上游接口异常，日志里有 URL 和状态码；`/v2/bili` 目前长期 500，可以先从 `HOT_SOURCES` 去掉 |
| 榜单显示不全 | 桌面「榜单显示」里的「最多显示」默认受尺寸限制，宽尺寸可用双列；也可以调小 `HOT_LIMIT` |
| 手机没有立刻刷新 | 服务端返回成功只代表数据已落库，桌面刷新被系统合并节流，省电模式下约 1 分钟 |

本地版本水位 `.hot_search_to_beacon_state.json` 属于运行数据，已被仓库忽略，不要提交。
