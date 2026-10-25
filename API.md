# Beacon API 文档

本文对应当前 Go 与 Cloudflare Workers 实现。部署与客户端使用见 [README](README.md)，开发约束见 [后端规范](BACKEND_GUIDELINES.md)。示例地址、ID和凭据均为占位值。

- [认证与通用约定](#认证与通用约定)
- [普通消息与兼容接口](#普通消息与兼容接口)：原生消息、操作按钮、幂等、Bark、客户端与管理接口。
- [实时数据主题与小组件发送 API](#实时数据主题与小组件发送-api)：预创建、progress/metric/list/text等数据、版本、删除与同步。
- [管理接口请求与响应](#管理接口请求与响应)
- [接收与实时协议](#接收与实时协议)

## 认证与通用约定

| 凭据 | 用途 | 不能代替 |
| --- | --- | --- |
| 管理令牌 | `/api/admin/*` 管理整个实例 | 设备接收凭据、推送凭据 |
| 一次性绑定码 | `/api/v1/bind` 请求体，10分钟有效且仅用一次 | 其他令牌 |
| 设备推送密钥 | 原生消息、Bark、未关联应用主题上报与版本查询 | 管理或接收凭据 |
| 应用令牌 | 原生消息、所属应用主题上报与版本查询 | Bark设备密钥、管理或接收凭据 |
| 设备接收凭据 | 能力查询、同步、确认接收、WebSocket、撤销自身 | 管理或推送凭据 |

认证放在 `Authorization: Bearer <凭据>` 请求头；Bark路径和绑定请求除外。管理接口不接收查询参数，WebSocket不接收URL中的凭据。不要把真实凭据写进仓库、日志或分享的请求示例。

JSON请求使用 `Content-Type: application/json`，原生请求严格校验字段、重复字段和UTF-8；请求体最多512 KiB。消息标题/正文限制按UTF-8字节计算，不是字符数。时间戳除Bark响应 `timestamp` 为秒外，`*_ms` 均为Unix毫秒。服务端生成的设备/应用/消息标识和接收游标应作为不透明值处理。

原生错误通常为 `{"error":"错误码"}`；Bark使用兼容错误响应。HTTP成功表示已保存或操作完成，不表示通知已展示，更不表示用户已读。普通消息的ACK表示客户端接收。WebSocket仅用于接收，脚本上传继续使用HTTP。

## 普通消息与兼容接口

Go 和 Cloudflare Workers 两种服务端均支持以下接口。先在 App 中绑定服务器，再在连接设置中点击「复制推送密钥」。下面示例中的 `https://push.example.com` 替换为自己的服务地址，`YOUR_PUSH_KEY` 替换为设备推送密钥。推送密钥允许向对应设备发送消息，请勿公开；绑定码和管理令牌不能代替它。App 的连接设置也提供可直接复制的推送示例。

### Beacon 原生接口

向 `POST /api/v1/push` 发送 JSON，通过 `Authorization: Bearer <密钥>` 认证：

```bash
curl -X POST 'https://push.example.com/api/v1/push' \
  -H 'Authorization: Bearer YOUR_PUSH_KEY' \
  -H 'Content-Type: application/json' \
  -d '{"title":"下载完成","subtitle":"NAS","body":"文件已下载","priority":5}'
```

| 字段 | 说明 |
| --- | --- |
| `mode` | 可选，省略或 `"event"` 发送普通事件；`"state"` 走下文数据主题流程 |
| `body` | 必填，非空正文，最多 65536 UTF-8 字节 |
| `title` | 可选，标题，最多 1024 UTF-8 字节 |
| `subtitle` | 可选，副标题，最多 1024 UTF-8 字节 |
| `priority` | 可选，0–10 的整数；设备推送密钥默认使用 5 |
| `actions` | 可选，最多 1 个操作按钮，格式为 `[{"title":"查看日志","url":"https://example.com/logs"}]`；省略或空数组表示没有按钮 |
| `group` | 可选，消息分组，最多 128 UTF-8 字节 |
| `ttl` | 可选，消息投递有效期，单位秒，0–604800 的整数；省略或 0 使用七天默认值 |
| `schema_version` / `blocks` | 普通事件也可附结构化块；有块时schema为1，块限制与下文通用格式相同；不会因此创建数据主题 |

优先级 0–1 仅保存消息，2–3 静默通知，4–7 普通通知，8–10 重要通知。原生接口也接受服务端管理中创建的应用推送令牌；省略 `priority` 时使用该应用的默认优先级。

成功返回 HTTP 200，响应包含消息的 `id`、`sequence`、`expires_at_ms` 等信息。使用应用令牌发送时还包含 `application_id` 与 `application_name`；它们由服务器生成，不能由发送方指定。

正文可直接发送 Markdown，无需额外格式字段，原生 API 与 Bark 接口均适用。Android 客户端在详情页渲染标题、加粗、斜体、列表、引用、链接和代码块，并提供「查看原文」切换；通知与消息列表显示去除排版标记的短摘要。复制、分享和搜索仍使用原始正文。Markdown 中前五个不同的有效 HTTPS 图片地址提供点击加载入口，默认不下载图片；网页链接点击后确认打开。HTML 只显示为文本，暂不支持表格、公式等扩展语法。正文上限为 65536 UTF-8 字节。

#### 带操作按钮的消息

每个操作必须包含 `title` 和 `url`，不接受其他字段。`title` 不能全为空白或包含控制字符，输入最多 64 UTF-8 字节，展示文案最多四个字符，超出自动截断；`url` 最多 2048 UTF-8 字节，必须为有效 HTTPS 地址，不能包含用户名或密码。`actions: null`、超过 1 个按钮或无效字段返回 `400 invalid_message`。

```bash
curl -X POST 'https://push.example.com/api/v1/push' \
  -H 'Authorization: Bearer YOUR_PUSH_KEY' \
  -H 'Content-Type: application/json' \
  -d '{"title":"构建失败","body":"主分支构建失败，请查看日志","group":"CI","actions":[{"title":"查看日志","url":"https://ci.example.com/builds/123"}]}'
```

App 在消息详情展示按钮和目标地址，点击后交给系统浏览器或对应应用打开。收到消息不会自动打开链接，也不会代用户发起带鉴权的请求。按钮随历史消息和备份保存，HTTP 补收与即时接收均支持；幂等判断包含按钮标题、地址和排列顺序。

Bark 的 `url` 在详情页显示为“打开链接”按钮，Bark 接口不支持 `actions` 数组。

需要自动重试的脚本可添加 `Idempotency-Key` 请求头（1–128 个不含空格的可见 ASCII 字符）。同一发送凭据下，相同键与相同内容在首次成功后七天内返回原消息，不重复保存或创建投递任务；同键不同内容返回 `409 idempotency_conflict`。消息已确认、已过期或历史已清理时仍返回原结果，不重新投递。换用不同凭据、超过七天或不带幂等键时会创建新消息。每个业务事件使用一个新键，失败重试时保持键和内容一致。

```bash
curl -X POST 'https://push.example.com/api/v1/push' \
  -H 'Authorization: Bearer YOUR_PUSH_KEY' \
  -H 'Idempotency-Key: build-20261012-001' \
  -H 'Content-Type: application/json' \
  -d '{"title":"构建完成","body":"构建已通过","group":"CI","ttl":3600}'
```

有效期按服务端时间计算。到期后 HTTP 补收和 WebSocket 均停止投递，后台维护随后清理过期正文；已保存到手机的历史不会随有效期到期而消失。手机消息卡片显示分组与应用名称，点击标签可精确筛选，也可在搜索栏输入 `group:CI` 或 `app:完整应用ID`。普通搜索同时匹配正文、标题、分组、应用名称和应用 ID。

服务端接收成功表示消息已保存，不代表手机已经收到或展示通知。

### Bark 兼容接口

支持自定义服务器地址的 Bark 第三方软件，可以直接通过 Beacon 服务推送到自己的 App。如果软件要求填写完整的 Bark 推送链接，填：

```text
https://push.example.com/YOUR_PUSH_KEY
```

如果软件分别要求服务器地址和 Device Key，则分别填写 `https://push.example.com` 和设备推送密钥。Bark 接口使用设备推送密钥，应用推送令牌请使用上面的原生接口。只支持固定 Bark 官方地址、无法修改服务器的软件不能通过此方式接入。

支持以下 GET 路径，消息内容作为路径片段时需要 URL 编码，尤其是 `/`、`?`、`#` 等字符：

```text
GET /<密钥>/<正文>
GET /<密钥>/<标题>/<正文>
GET /<密钥>/<标题>/<副标题>/<正文>
```

也可以通过查询参数发送，使用 `curl --data-urlencode` 自动编码中文和特殊字符：

```bash
curl --get 'https://push.example.com/YOUR_PUSH_KEY' \
  --data-urlencode 'title=下载完成' \
  --data-urlencode 'body=文件已下载到 NAS'
```

支持 `POST /push`，请求体可以是 JSON 或 `application/x-www-form-urlencoded` 表单，用 `device_key` 指定设备：

```bash
curl -X POST 'https://push.example.com/push' \
  -H 'Content-Type: application/json' \
  -d '{"device_key":"YOUR_PUSH_KEY","title":"下载完成","body":"文件已下载到 NAS"}'
```

也支持向 `POST /<密钥>` 发送同样的 JSON 或表单，此时无需填写 `device_key`。参数优先级为路径 > 查询参数 > 请求体。成功响应示例：

```json
{"code":200,"message":"success","timestamp":1791763200,"id":"消息 ID","sequence":1}
```

兼容范围：

- 支持 `title`、`subtitle`、`body`，文本长度限制与原生接口一致。
- `level=passive` 或 `sound=none` 对应优先级 2；`level=timeSensitive`、`level=critical` 对应优先级 8；其余默认使用 5。Android 通知效果仍受系统通知权限和通知渠道设置影响。
- 接收 `image`、`icon`、`url`、`sound` 扩展字段；链接字段仅保留有效的 HTTPS 地址。Bark 的 iOS 铃声和 critical alert 权限不等同于 Android 上的效果。
- 接收 `ciphertext`、`iv` 加密字段。在 Android「设置 → 服务器配置 → Bark 消息解密」配置与发送方一致的 AES 加密密钥和默认 IV；先完成服务器绑定。AES-CBC 密钥需为16、24或32个 UTF-8 字节，默认 IV 为16字节，均按原始文本输入，不是十六进制/Base64解码输入。消息自带 IV 时优先使用消息中的值。
- 密钥仅通过 Android Keystore 加密保存在手机，不上传服务端、不回显；服务器只转发密文。保存配置后自动尝试解密尚未解密的历史消息。可替换密钥，或通过「移除解密配置」停止后续自动解密；移除保留服务器绑定和已解密的历史正文。重新绑定服务器或清除本机配置后需重新配置解密密钥。
- 支持 `group` 与 `ttl`；Beacon 的 `ttl` 单位为秒，范围与原生接口一致，JSON 可使用整数或整数字符串，表单/查询参数使用整数字符串。
- Bark 接口也支持 `Idempotency-Key` 请求头，作用域、有效窗口与原生接口相同。
- `badge`、`action` 等未实现参数会被忽略，不影响正常文本推送。

### 常见错误

| HTTP 状态 | 含义 |
| --- | --- |
| `400` | 消息或参数格式不正确，例如正文为空、文本超长 |
| `401` | 推送密钥无效或已撤销 |
| `413` | 请求过大 |
| `415` | Bark POST 请求的 Content-Type 不受支持，请使用 JSON 或表单 |
| `409` | 幂等键已用于不同内容，请为新事件使用新键 |
| `429` | 推送频率超过限制，稍后重试 |
| `507` | 服务端存储容量达到限制 |


### 客户端与管理接口

以下接口在两种服务端中均支持。除绑定和健康检查外，认证均使用 `Authorization: Bearer <对应凭据>`；推送密钥不能代替接收凭据或管理令牌。

| 方法 | 路径 | 认证 | 用途 |
| --- | --- | --- | --- |
| GET | `/healthz` | 无 | 健康检查 |
| POST | `/api/v1/bind` | 一次性绑定码（请求体） | 绑定设备，字段为 `binding_code`、`device_name` |
| GET | `/api/v1/capabilities` | 设备接收凭据 | 查询服务端能力与协议 |
| GET | `/api/v1/realtime/config` | 设备接收凭据 | 获取即时连接配置 |
| GET / WebSocket Upgrade | `/api/v1/realtime/stream` | 设备接收凭据 | 建立即时接收连接 |
| GET | `/api/v1/messages` | 设备接收凭据 | 分页获取消息，支持 `after`、`limit`、`pending=1` |
| POST | `/api/v1/messages/ack` | 设备接收凭据 | 确认接收，请求体为 `{"ids":["消息 ID"]}` |
| POST | `/api/v1/receipts/status` | 设备接收凭据 | 查询消息回执，请求体为 `{"ids":["消息 ID"]}` |
| POST | `/api/v1/device/revoke` | 设备接收凭据 | 撤销当前设备 |
| GET | `/api/admin/overview` | 管理令牌 | 查询设备、应用和存储概况 |
| GET | `/api/admin/metrics` | 管理令牌 | 按路由模板聚合请求量、5xx与延迟，以及投递队列快照和活动连接数（Go/Workers；不含设备身份、路径参数或凭证） |
| GET | `/api/admin/ready` | 管理令牌 | 就绪检查；200返回`{"ready":true}`，不可用返回503 `not_ready`；不替代healthz |
| POST | `/api/admin/binding` | 管理令牌 | 创建绑定码，字段为 `name` |
| POST | `/api/admin/test` | 管理令牌 | 向设备发送测试消息，字段为 `device_id`、`title`、`subtitle`、`body`、`priority` |
| POST | `/api/admin/revoke` | 管理令牌 | 撤销设备，字段为 `device_id` |
| POST | `/api/admin/devices/delete` | 管理令牌 | 删除设备及关联数据，字段为 `device_id` |
| POST | `/api/admin/messages/clear` | 管理令牌 | 清空服务端消息，请求体为 `{}` |
| POST | `/api/admin/applications` | 管理令牌 | 创建应用令牌，字段为 `device_id`、`name`、`default_priority` |
| POST | `/api/admin/applications/revoke` | 管理令牌 | 撤销应用令牌，字段为 `id` |
| POST | `/api/admin/applications/delete` | 管理令牌 | 删除已撤销的应用令牌及其消息，字段为 `id` |

例如，不通过 App 管理页面也可以创建绑定码：

```bash
curl -X POST 'https://push.example.com/api/admin/binding' \
  -H 'Authorization: Bearer YOUR_ADMIN_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"name":"我的手机"}'
```

Go 服务端需要设置 `BEACON_WEB_ADMIN_TOKEN` 才会启用管理接口，要求为 64 位十六进制字符串，也可通过 `BEACON_WEB_ADMIN_TOKEN_FILE` 从文件读取；Workers 必须设置至少 32 字符的 `ADMIN_TOKEN` 才能正常提供服务。

## 实时数据主题与小组件发送 API

小组件没有单独的上传地址。发送方把**数据主题的完整最新内容**提交到 `POST /api/v1/push`，手机收到后，再在小组件设置中选择展示内容与模板。仅发送普通通知不会自动生成小组件数据。

接入顺序：绑定设备 → 管理员创建数据主题（关联应用可选）→ 手机同步目录 → 长按桌面添加 Beacon 小组件并绑定主题 → 获取对应推送密钥并上报数据。尚未上报时显示“等待数据”，收到数据后自动匹配样式。详见[字段说明](#上报字段)、[各类数据格式](#数据块格式与模板对应关系)、[更新与删除](#更新版本任务与删除)、[接口及错误](#同步管理与错误处理)。

### 可直接运行的 curl 示例

以下使用 Bash / Linux / macOS；Windows PowerShell 请使用 `curl.exe`，把同样的 JSON 保存为 UTF-8 文件，再通过 `--data-binary @payload.json` 提交。

```bash
export BEACON_URL='https://push.example.com'
export BEACON_PUSH_KEY='替换为设备推送密钥或应用令牌'

# 先确认已部署的服务端支持数据主题协议。
curl --fail-with-body "$BEACON_URL/api/v1/push-capabilities" \
  -H "Authorization: Bearer $BEACON_PUSH_KEY"

# 使用前先在「服务器管理 → 数据主题」创建 widget/demo-progress。
# 第一次上报 revision=1；再次运行需查询版本并递增。
# 当前采样时间用于计算“数据陈旧”，必须随每次新数据一起更新。
OBSERVED_AT_MS="$(($(date +%s) * 1000))"
curl --fail-with-body -X POST "$BEACON_URL/api/v1/push" \
  -H "Authorization: Bearer $BEACON_PUSH_KEY" \
  -H 'Content-Type: application/json' \
  --data-binary @- <<JSON
{
  "mode": "state",
  "schema_version": 1,
  "topic_key": "widget/demo-progress",
  "revision": 1,
  "observed_at_ms": $OBSERVED_AT_MS,
  "fresh_for_seconds": 600,
  "ttl": 86400,
  "title": "账户额度",
  "body": "短周期剩余24%，长周期剩余62%",
  "blocks": [
    {"kind":"progress","id":"short","label":"短周期剩余","value":24,"max":100,"unit":"%","tone":"critical"},
    {"kind":"progress","id":"long","label":"长周期剩余","value":62,"max":100,"unit":"%","tone":"normal"}
  ]
}
JSON
```

PowerShell 发送 UTF-8 `payload.json` 文件：

```powershell
$env:BEACON_URL = 'https://push.example.com'
$env:BEACON_PUSH_KEY = '替换为设备推送密钥或应用令牌'
curl.exe --fail-with-body -X POST "$env:BEACON_URL/api/v1/push" -H "Authorization: Bearer $env:BEACON_PUSH_KEY" -H 'Content-Type: application/json' --data-binary '@payload.json'
```

成功返回 HTTP 200，JSON 包含提交的内容和服务端生成的 `application_id`、`application_name`、`accepted_at_ms`、`expires_at_ms`、`stale_at_ms`、`deleted`、`unchanged`。这表示数据已接受，不代表桌面已经刷新；数据主题响应不含普通消息的 `id/sequence`。

设备推送密钥上报的数据归入“未关联应用”；应用令牌上报的数据归入该应用。归属由凭据确定，不能在请求 JSON 中填写 `device_id` 或 `application_id` 改变它。接收凭据和管理令牌不能用于上报。

### 上报字段

除删除请求外，字段如下。所有 `*_at_ms` 均为 Unix **毫秒**时间戳，`ttl` 和 `fresh_for_seconds` 使用**秒**。

| 字段 | 必填 | 类型与含义 |
| --- | --- | --- |
| `mode` | 是 | 固定为 `"state"`；省略会走普通消息流程 |
| `schema_version` | 是 | 整数，当前固定为 `1` |
| `topic_key` | 是 | 同一来源内稳定的主题标识，UTF-8 1–128 字节，不含控制字符；例如 `nas/disk` |
| `revision` | 是 | 1–9007199254740991 的整数；每次内容更新必须递增 |
| `body` | 是 | 非空摘要或整段文字，最多 65536 UTF-8 字节；普通展示及降级使用 |
| `title` / `subtitle` | 否 | 标题 / 副标题，各最多 1024 UTF-8 字节；桌面会按尺寸截断 |
| `blocks` | 否 | 数据块数组，最多 16 块、序列化后最多 32 KiB；整段文字模板可省略 |
| `operation` | 否 | 省略或 `"upsert"` 表示完整覆盖；`"delete"` 使用下文的精简格式 |
| `state_type` | 否 | 省略或 `"persistent"` 表示持续主题；`"task"` 表示有终态的任务 |
| `status` | 任务必填 | 任务使用 `running/paused/completed/failed/cancelled`；持续主题省略 |
| `observed_at_ms` | 否 | 采样时间，非负整数，不得超前服务端时间超过 5 分钟；设置新鲜度时请同时传入实际采样时间 |
| `fresh_for_seconds` | 否 | 0 表示不启用陈旧提示；正数不得超过有效 TTL。陈旧时间为 `observed_at_ms + fresh_for_seconds × 1000` |
| `ttl` | 否 | 0 或省略为 604800 秒（7 天），最大 604800；从服务端接受时间计算，到期后同步移除数据 |
| `priority` | 否 | 0–10，沿用通知优先级；不会提高桌面刷新频率 |
| `group` | 否 | 消息分组，最多 128 UTF-8 字节 |
| `actions` | 否 | 沿用普通消息格式，最多一个 `{"title":"查看","url":"https://example.com"}` HTTPS 操作；不是小组件模板参数 |

每次上报是**完整快照，不是局部补丁**：没有再次提交的块会消失。`blocks[].id` 在同一主题内保持稳定，小组件据此保存选择与顺序；删除或改名后需重新选择。模板、字体、颜色及实例绑定在手机端配置，不要向上报 JSON 加入 `template`、`color` 等未支持字段。协议不接受早期设想中的 `data.type`、`fallback_text` 或 `collapse_key`；降级文案请用 `body`。

### 数据块格式与模板对应关系

块通用字段：`kind` 为类型、`id` 为块内唯一标识（1–64 UTF-8 字节）、`label` 为显示名称（最多 128 字节）、`unit` 为单位（最多 32 字节）、`tone` 为 `normal/warning/critical`。未指定 `tone` 时由客户端采用默认配色，手动配色优先。数值必须有限且绝对值不超过 10^15。

| 数据块 | 必需或关键字段 | 当前桌面模板 |
| --- | --- | --- |
| `progress` | `value`、`max`；`min` 默认 0，须满足 `min ≤ value ≤ max` 且 `max > min` | 横条、柱图、单环、嵌套环、独立小环、纯数字 |
| 不确定进度 | `kind:"progress", indeterminate:true`，不要同时传 `value/min/max/trend` | 横向进度条或纯数字；需要百分比的环形不可选 |
| `metric` | `value`；`min/max` 若提供必须成对；`trend` 可为 `up/down/flat` | 无范围可用纯数字/横条；有范围可用环形/柱图 |
| `list` | `items` 1–12 项，每项有唯一 `id` 和非空 `text`，可带 `label/tone` | 文字列表：2×宽单列、4×宽双列，3×宽按可用空间展示 |
| `segments` | 正数 `total`，`items` 1–6 项；每项 `id/label/value`，值非负且总和不超过 `total` | 占比圆环；未占满部分作为剩余空间 |
| `sparkline` | `points` 1–120 项，每项 `at_ms/value`，时间非负且严格递增 | 迷你趋势图 |
| `kv` | `items` 1–12 项，每项 `id/label/text` | App 详情渲染，不直接作为桌面数值块 |
| `countdown` | `target_at_ms` 非负毫秒时间戳 | App详情及独立倒计时小组件；按分钟显示，不需要脚本每分钟重复上报 |
| `timeline` | `items` 1–12 项：`id/label/at_ms/status`；状态为 `pending/active/completed/failed` | App 详情渲染 |
| `pipeline` | `items` 1–12 项：`id/label/status`；状态为 `pending/running/completed/failed/skipped` | App 详情渲染 |
| `alert` | 非空 `text`（最多 2048 UTF-8 字节），可带 `tone` | App 详情渲染 |
| 整段文字 | 主题顶层 `body`，不需要新增块类型 | 整段文字 |

文字列表的 `items[].text` 和其他条目说明最多 2048 UTF-8 字节；这是协议上限，桌面仍按宽度、字号和安全边距截断。单环选择 1 项、嵌套环 2–3 项、独立小环 2–4 项；柱图要求相同单位与范围。每种尺寸的数量和布局还有额外限制，手机会置灰不兼容模板并给出原因。

以下均为**完整请求体**，可保存为 `payload.json` 后统一发送：

```bash
curl --fail-with-body -X POST "$BEACON_URL/api/v1/push" \
  -H "Authorization: Bearer $BEACON_PUSH_KEY" \
  -H 'Content-Type: application/json' \
  --data-binary @payload.json
```

**纯数字 / 有范围的数值**：`balance` 无范围，适合纯数字；`cpu` 有范围，可单独选为圆环或进度条。

```json
{
  "mode":"state", "schema_version":1, "topic_key":"widget/server", "revision":1,
  "title":"服务器", "body":"CPU 24%，余额128.50元",
  "blocks":[
    {"kind":"metric","id":"cpu","label":"CPU","value":24,"min":0,"max":100,"unit":"%","trend":"up"},
    {"kind":"metric","id":"balance","label":"余额","value":128.5,"unit":"元","trend":"down"}
  ]
}
```

**热搜榜 / 文字列表**：数组顺序就是原始排名；最多12条，超出时发送方先截取 Top N。

```json
{
  "mode":"state", "schema_version":1, "topic_key":"widget/news", "revision":1,
  "title":"今日热搜", "body":"今日热搜前三条",
  "blocks":[{"kind":"list","id":"news","label":"热搜榜","items":[
    {"id":"news-1","text":"第一条新闻标题"},
    {"id":"news-2","text":"第二条新闻标题"},
    {"id":"news-3","text":"第三条新闻标题"}
  ]}]
}
```

**整段文字**：标题之外的内容放在 `body`，`\n` 表示换行；手机选择“整段文字”。

```json
{
  "mode":"state", "schema_version":1, "topic_key":"widget/notice", "revision":1,
  "title":"值班公告", "body":"今日值班：张三\n维护时间：22:00—23:00\n请提前保存工作。"
}
```

**占比圆环**：一个环用不同颜色显示组成；这与多项独立进度的“嵌套环”不同。以下剩余20 GB由手机显示为剩余空间。

```json
{
  "mode":"state", "schema_version":1, "topic_key":"widget/disk", "revision":1,
  "title":"NAS 空间", "body":"总量100 GB，已用80 GB",
  "blocks":[{"kind":"segments","id":"disk","label":"存储分布","total":100,"unit":"GB","items":[
    {"id":"images","label":"图片","value":30},
    {"id":"videos","label":"视频","value":40},
    {"id":"other","label":"其他","value":10}
  ]}]
}
```

**迷你趋势图**：发送方提供历史点，Beacon 不会自动将历次上报拼成历史曲线。示例时间为占位历史采样时间，实际发送请使用自己的时间序列。

```json
{
  "mode":"state", "schema_version":1, "topic_key":"widget/latency", "revision":1,
  "title":"访问延迟", "body":"最近一次延迟42 ms",
  "blocks":[{"kind":"sparkline","id":"latency","label":"延迟","unit":"ms","points":[
    {"at_ms":1710000000000,"value":35},
    {"at_ms":1710000060000,"value":51},
    {"at_ms":1710000120000,"value":42}
  ]}]
}
```

**进度分段提示颜色**：替换进度示例中的一块。`bands` 最多5段，`until` 严格递增，最后一段必须等于 `max`。下例值越大风险越高；额度剩余越少风险越高时由发送方反向设置语义。

```json
[
  {"kind":"progress","id":"disk-used","label":"已用空间","value":85,"max":100,"unit":"%",
   "bands":[{"until":70,"tone":"normal"},{"until":90,"tone":"warning"},{"until":100,"tone":"critical"}]}
]
```

### 更新版本、任务与删除

固定 `topic_key` 和块 `id`，只提高 `revision` 并提交完整新内容。例如进度24变为25，使用相同主题、`revision:2`、`value:25`。若更换推送密钥为另一应用令牌，即使 `topic_key` 相同也属于另一主题，不会更新旧组件。

```bash
# 用与上报相同的密钥查询版本；已登记但未上报返回0，未登记返回404。
curl --fail-with-body "$BEACON_URL/api/v1/states/meta?topic_key=widget%2Fdemo-progress" \
  -H "Authorization: Bearer $BEACON_PUSH_KEY"
```

已登记但未上报时返回 `revision:0`；未登记或已被管理员删除返回404 `topic_not_found`。查询返回 `revision/deleted/status`。新写入使用更高版本；单一发送方可读取版本后加一，并持久化待重试的请求。**这不是原子版本申请**，多个发送方必须自行协调或使用不同主题。网络超时时原样重试同一版本和同一内容，包括原采样时间；同版本相同内容返回 `unchanged:true`，同版本不同内容返回409，旧版本也返回409。数据主题不使用普通事件的 `Idempotency-Key` 去重。

下载等有生命周期的任务增加 `state_type:"task"`，`status` 支持 `running/paused/completed/failed/cancelled`：

```json
{
  "mode":"state", "schema_version":1, "topic_key":"download/job-001", "revision":1,
  "state_type":"task", "status":"running", "title":"镜像下载", "body":"已下载40%",
  "blocks":[{"kind":"progress","id":"download","label":"下载进度","value":40,"max":100,"unit":"%"}]
}
```

完成时以更高版本上报 `status:"completed"` 和最终进度；需要完成提醒时另发普通消息。已完成、失败、取消或删除的任务不能重新回到运行态，下一次下载使用新 `topic_key`。持续主题和任务主题也不能在同一身份下相互转换。未知总大小时，把进度块改为 `{"kind":"progress","id":"download","label":"下载中","indeterminate":true}`。

删除使用比当前水位更高的版本，并且**只能提交以下五个字段**，不要附加标题、正文或数据块，即使为空也不传：

```json
{"mode":"state","schema_version":1,"topic_key":"widget/demo-progress","revision":3,"operation":"delete"}
```

这里的3仅为示例，若当前版本已达到3或更高需继续递增。TTL 到期会移除数据，但不会遗忘版本水位；持续主题可用更高版本重新上报，旧版本重试不会复活它。`fresh_for_seconds` 过期只显示陈旧提示、保留最后数据，与 TTL 删除不同。

### 预创建主题与删除规则

主题身份是 `device_id + application_id + topic_key`，创建后不修改；名称最多80个字符，可随时修改，不消耗数据 revision。未关联应用时 `application_id` 为空，使用设备推送密钥；关联应用时使用该应用令牌。推送凭据、接收凭据均无权修改主题目录。

管理员创建请求示例：

```json
{"device_id":"设备ID","application_id":"","name":"账户额度","topic_key":"widget/demo-progress","request_id":"调用方生成的唯一请求ID"}
```

使用管理令牌 POST 到 `/api/admin/topics/create`。`request_id` 必填，建议使用1–128个ASCII字符；同设备下重试保持相同ID和内容，返回原主题，内容冲突返回409。`topic_key` 可省略，由服务端生成，或使用现有协议允许的1–128字节非控制字符。返回 `{"topic":{...}}`，含 `config_version`（初始1）、创建与更新时间；预创建不写伪造状态数据，数据 revision 初始0。

`update` 请求使用 `device_id/application_id/topic_key/config_version/name`；`delete` 使用相同身份和 `config_version`，返回删除事件摘要。`detail` 仅需身份字段。配置版本变化返回409。`topics/list` 的筛选分页与下表 states/list 相同，`state` 支持 `all/waiting/active/stale/expired`，返回目录名称、配置版本和数据摘要。设备接收目录快照不含已删除主题。

- **清空数据**：旧 `operation:"delete"` 或 `/api/admin/states/clear` 保留主题身份、绑定和数据版本水位；列表显示等待数据或已过期，允许按原任务规则续报。
- **删除主题**：管理员确认后直接删除目录、当前数据和该身份的版本水位，列表不保留“已删除”项，没有恢复入口。脚本此后上报或查询版本返回404；只有管理员能手动重新创建。新建同一标识属于新一轮数据，revision 可从1开始。仍在桌面上的旧绑定显示内容不可用，可重新选择主题。
- **兼容**：升级时已有数据和水位一次性迁移到目录。原脚本继续更新已登记主题；使用新标识前必须先创建。新客户端连接旧服务端隐藏预创建入口；旧客户端仍可接收数据，但重新创建同标识从低版本上报时应升级客户端以同步清除旧缓存水位。

`GET /api/v1/topics` 使用与 states 相同的 `cursor/snapshot_token/page_cursor/limit`，返回 `topics` 数组和 `server_id/has_more/next_cursor`；目录游标与数据游标分别保存。名称变化和删除通过增量同步；删除事件仅用于清除本机缓存，不占用主题名额。游标失效返回410 `state_cursor_expired` 并重新拉取快照。WebSocket 客户端以 `X-Beacon-Topics:1` 协商 `@beacon/topics_changed/1` 提示，再通过HTTP同步目录。

### 同步、管理与错误处理

| 接口 | 凭据与用途 |
| --- | --- |
| `POST /api/v1/push` | 推送密钥：上报事件或最新状态 |
| `GET /api/v1/states/meta?topic_key=...` | 推送密钥：查询该来源的版本水位 |
| `GET /api/v1/push-capabilities` | 推送密钥：协议、数据块与额度能力 |
| `GET /api/v1/topics` | 设备接收凭据：主题目录快照与游标增量同步 |
| `POST /api/admin/topics/create` | 管理令牌：预创建主题，支持幂等重试 |
| `POST /api/admin/topics/list` | 管理令牌：目录和最新数据摘要，含等待数据的主题 |
| `POST /api/admin/topics/detail` | 管理令牌：读取主题配置 |
| `POST /api/admin/topics/update` | 管理令牌：携带 config_version 修改名称 |
| `POST /api/admin/topics/delete` | 管理令牌：携带 config_version 硬删除主题和数据 |
| `GET /api/v1/states` | 设备接收凭据：快照与游标增量同步，由客户端管理分页与游标 |
| `POST /api/admin/states/list` | 管理令牌：筛选和分页，默认 50 条、最多 100 条 |
| `POST /api/admin/states/detail` | 管理令牌：读取指定身份的最新内容 |
| `POST /api/admin/states/clear` | 管理令牌：携带当前 revision 清空，版本变化返回 409 |

管理接口使用 `Authorization: Bearer <管理令牌>`，不支持查询参数。`list` 请求体可含 `device_id`、`application_id`、`search`、`state`、`limit`、`offset`；省略 `application_id` 表示全部来源，空字符串表示未关联应用。`state` 支持 `all/active/stale/expired/deleted`。返回 `topics/total/has_more/next_offset`。详情与清空请求体使用 `device_id/application_id/topic_key`，清空还必须包含 `revision`。这些接口在 Go 与 Workers 上保持一致。

能力探测新增 `topic_catalog_protocol:1`，并返回 `state_protocol:1`、`structured_schema_versions:[1]` 和支持的 `block_kinds`。旧服务端404或不支持时先升级服务端；普通 Bark 文本接口不能代替数据主题接口。

| HTTP / 错误码 | 处理方式 |
| --- | --- |
| 400 `invalid_message` | 核对必填正文、`schema_version`、类型/范围、块内唯一ID和未支持字段；数值不要传成字符串 |
| 401 `unauthorized` | 使用有效的设备推送密钥或应用令牌，核对是否撤销；不要使用接收或管理凭据 |
| 404 `topic_not_found` | 管理员先创建主题，核对设备及应用归属；删除后脚本不能自动重建 |
| 409 `topic_config_conflict` | 配置版本冲突或创建幂等键冲突，刷新目录后再操作 |
| 409 `stale_state_revision` | 查询元数据，避免旧采样覆盖较新数据，按实际需要生成更高版本 |
| 409 `state_revision_conflict` | 同版本内容不同；确认是否有多个写入者或重试时修改了采样时间 |
| 409 `invalid_state_transition` | 检查任务终态、删除后重启或任务/持续类型切换；新任务使用新主题 |
| 429 `push_rate_limited` | 遵循 `Retry-After`，退避并合并为最新快照 |
| 507 `storage_capacity_reached` | 检查主题数量、水位与存储容量，不要为每次采样创建一个新主题 |

当前每设备最多500个已登记主题（含等待数据、已清空或已过期主题；管理员删除主题后释放名额）、有效负载合计最多8 MiB；每应用每设备每分钟最多120次状态写入，每设备每分钟最多300次。桌面刷新不是上传频率保证；合并高频采样，进度通常按数秒节流。数据未变时也应按新鲜度和 TTL 计划定期续报，提高版本并使用新的采样时间。

`GET /api/v1/states` 是客户端接收接口：首次无游标读取固定快照，分页使用返回的 `snapshot_token/page_cursor`，最后一页获得 `next_cursor` 后再用 `cursor` 增量同步；HTTP 410 `state_cursor_expired` 时重新读取快照。脚本通常只需上报和版本查询，不需要实现此接收流程。

发送方持续通过 HTTP 上报，服务端通过 WebSocket 通知手机有更新，断线后通过 HTTP 补收。无需把上传改成 WebSocket。桌面更新会合并与限流（及时约5秒，均衡下任务约5秒、其他约15秒，省电约1分钟）；不适合每帧动画或保证每秒刷新。系统进度通知可展示一条主进度，多个进度的完整信息放在 App 和小组件。状态通知按主题开启，普通状态更新不应反复响铃；任务完成等事件可另发普通消息。

可运行脚本见 [examples/structured](examples/structured)，字段、格式及鉴权约束以上文为准。设计草稿和原生验收图仅保留在本地 `docs/design/` 或本地文档仓库，不纳入版本控制。


## 管理接口请求与响应

以下表格补充各管理操作的请求字段。所有管理请求均使用独立管理令牌；服务端的管理接口启用条件见前文。

| 方法和路径 | 请求体 | 成功结果 |
| --- | --- | --- |
| `GET /api/admin/overview` | 无 | 200；`devices/applications/storage/server_id/version/protocol/admin_protocol/topic_catalog_protocol` |
| `GET /api/admin/metrics` | 无 | 200；按路由模板统计，不能含密钥或正文 |
| `GET /api/admin/ready` | 无 | 200 `ready:true`；503 `not_ready`；独立管理鉴权，查询参数被拒绝 |
| `POST /api/admin/binding` | `{"name":"我的手机"}` | 201；`binding_code/device_name/expires_at_ms` |
| `POST /api/admin/test` | `device_id/title/subtitle/body/priority`；正文必填，priority省略为5 | 201；已保存消息 |
| `POST /api/admin/revoke` | `{"device_id":"设备ID"}` | 200；`revoked:true` |
| `POST /api/admin/devices/delete` | `{"device_id":"已撤销设备ID"}` | 200；`deleted/device_deleted`；必须先撤销 |
| `POST /api/admin/messages/clear` | `{}` | 200；`deleted`；包含待收消息及回执清理 |
| `POST /api/admin/applications` | `device_id/name/default_priority`，优先级0–10 | 201；包含应用 `id/name/token`，令牌只在创建时提供 |
| `POST /api/admin/applications/revoke` | `{"id":"应用ID"}` | 200；`revoked:true` |
| `POST /api/admin/applications/delete` | `{"id":"已撤销应用ID"}` | 200；`deleted/application_deleted`；必须先撤销 |
| `POST /api/admin/topics/create` | `device_id/name/request_id`；可选 `application_id/topic_key` | 200；`topic` 配置，数据尚未生成 |
| `POST /api/admin/topics/list` | 可选 `device_id/application_id/search/state/limit/offset` | 200；`topics/total/has_more/next_offset` |
| `POST /api/admin/topics/detail` | `device_id/application_id/topic_key` | 200；`topic` 配置 |
| `POST /api/admin/topics/update` | 上述身份加 `config_version/name` | 200；更新后的 `topic` |
| `POST /api/admin/topics/delete` | 上述身份加 `config_version` | 200；删除事件摘要 `topic`；目录及数据已硬删除 |
| `POST /api/admin/states/list` | 同类筛选分页 | 200；仅已有数据的摘要，保留旧接口语义 |
| `POST /api/admin/states/detail` | `device_id/application_id/topic_key` | 200；`device_id/device_name/state` |
| `POST /api/admin/states/clear` | 上述身份加数据 `revision` | 200；`cleared/revision`；保留主题和版本水位 |

创建主题的 `request_id` 为同设备下的幂等标识，重试保持不变；`topic_key` 省略时由服务端生成。`application_id` 为空表示未关联应用，有值时必须属于该设备。目录名称最多80个Unicode字符，身份创建后不可更改。配置 `config_version` 从1开始，与上报数据 `revision` 分开；版本冲突返回409 `topic_config_conflict`。

`topics/list` 默认每页50、上限100，offset上限1000000，search最多128个Unicode字符。省略application_id表示全部，空字符串表示未关联应用。目录状态使用 `all/waiting/active/stale/expired`；旧 `states/list` 还支持 `deleted`（表示数据清空而非主题目录删除）。目录删除后不列出、不提供restore接口。删除和重建同一标识属于新数据生命周期。

## 接收与实时协议

### 绑定与撤销

`POST /api/v1/bind` 的JSON为 `{"binding_code":"一次性绑定码","device_name":"手机名称"}`；成功返回设备及服务端身份、`receive_token/push_key/protocol`。客户端必须安全保存凭据，不向普通页面或备份泄露接收令牌。

撤销当前设备使用 `POST /api/v1/device/revoke`，接收凭据鉴权，请求体包含绑定时的 `server_id/device_id`，服务端验证与当前身份匹配。撤销不是删除历史或已登记目录的通用替代操作。

### 消息同步与接收确认

- `GET /api/v1/messages?after=0&limit=25&pending=1`：after是非负消息序号，limit范围1–100、默认25；pending只接受省略或1。客户端应分别补收待收消息和推进增量序号。
- `POST /api/v1/messages/ack`：`{"ids":["消息ID"]}`，一次1–100条；成功返回 `accepted:true`。本地持久化成功后才ACK。
- `POST /api/v1/receipts/status`：相同ids结构，一次1–100条，用于判断回执及服务端记录是否仍可确认。不要将查询结果当成已读状态。
- `/api/v1/capabilities` 返回服务器/设备身份、时间、传输方式、消息及结构化能力、保留期协议。按能力字段协商，不能仅凭版本名称猜测支持情况。

### WebSocket

先 `GET /api/v1/realtime/config` 获取服务端即时连接配置，再使用接收凭据连接 `/api/v1/realtime/stream`。遵循返回的协议范围；当前HTTP协议1、实时协议2、消息信封协议1。该通道仅接收，不支持脚本向Socket发送任意业务数据。

| 协商头 | 值 | 行为 |
| --- | --- | --- |
| `X-Beacon-Heartbeat` | `1` | 协商心跳控制 |
| `X-Beacon-Reception` | `batch` | 配合心跳控制启用普通消息批量接收 |
| `X-Beacon-State` | `1` | 接收 `@beacon/state_changed/1` 数据更新提示 |
| `X-Beacon-Topics` | `1` | 接收 `@beacon/topics_changed/1` 目录更新提示 |

目录/状态提示不携带完整业务数据，收到后通过各自HTTP同步接口获取。断线恢复也必须补收，不能以WebSocket在线代替HTTP版本和游标校验。状态与目录快照最多每设备保留两个、有效10分钟，增量日志按24小时和10000条上限清理；游标失效需重新快照。

### 倒计时与组合面板

倒计时沿用已有结构化块，不增加服务端轮询或新接口。例如在已经预创建的主题上发送：

```json
{
  "mode": "state",
  "schema_version": 1,
  "topic_key": "widget/reset",
  "revision": 1,
  "observed_at_ms": 1792972800000,
  "fresh_for_seconds": 14400,
  "ttl": 86400,
  "title": "窗口重置",
  "body": "以目标时间计算剩余时间",
  "blocks": [
    {"kind":"countdown","id":"reset","label":"下次重置","target_at_ms":1792980000000}
  ]
}
```

示例时间戳须替换为实际观察/目标时间，后续上报递增revision。目标时间到达只表示倒计时结束，不代表额度已重置或任务完成；业务结果仍由发送方确认后上报。手机息屏不绘制，分钟级显示受系统调度影响，不承诺逐秒/精确到分钟；较旧数据停止动态倒计时并提示等待更新。

组合面板使用同一主题已有blocks：数字与横条需要三项数值（后两项明确范围），环形与数值需要三项数值（第一项明确范围），数值与榜单需要一项数值和一组list。布局、显示短名称及点击行为属于手机实例设置，不是推送payload字段；发送方不指定桌面尺寸或自由布局。榜单完整内容通过用户主动开启的只读详情入口查看，未增加逐条新闻链接或自动打开外链的协议。

### 实现依据

- [Go路由](server/internal/httpapi/api.go)、[管理路由](server/internal/httpapi/admin.go)、[数据模型与限制](server/internal/domain/model.go)、[主题实现](server/internal/store/topics.go)。
- [Workers路由](workers/src/api.ts)、[协议号](workers/src/protocol.ts)、[结构化校验](workers/src/blocks.ts)。
- [共享协议样例](contract/)、[可运行结构化示例](examples/structured/README.md)。新增接口或修改语义时同步更新本文及两种后端的测试。

### 运维统计快照

`GET /api/admin/metrics` 的既有 `uptime_seconds/requests/server_errors/routes` 保留，Go与Workers均提供。
`routes`仅保存固定路由模板或`unmatched/other`，每项包含`requests/server_errors/mean_ms/slowest_ms/under_100ms/under_1s`。
延迟分桶互斥：小于100ms、小于1s但不少于100ms，其余为不少于1s；不能当成累计直方图。请求统计从进程/DO实例初始化后累计，重启/休眠重建后可重置。

新增`active_connections`为当前活动连接数，`delivery`为最多缓存5秒的数据库队列快照：

- `sampled_at_ms`：实际采样时间，Unix毫秒。
- `queued_realtime_messages`：待实时发布任务数；不等于待客户端确认消息数。
- `oldest_realtime_age_ms`：队列中最早消息接受至采样的时间；空队列为0。
- `retrying_realtime_messages`：已经发生投递重试的排队任务数。

快照读取失败返回503 `observability_unavailable`（底层不可恢复存储错误仍可能映射500 `storage_error`），不会改变已提交消息；客户端ACK仍使用原接口独立确认。`/healthz`为轻量存活检查；管理员就绪检查只做轻量DB可读检查及Go连接hub退出状态，不是写入/容量保证，不执行写探针或全表扫描。两个新管理能力均不接受URL凭据，沿用管理请求的无缓存策略。
