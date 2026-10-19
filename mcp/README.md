# Beacon MCP

把 Beacon 的推送接口接入支持 MCP 的客户端（Codex、Claude Code、Claude Desktop 等），让 AI 在任务结束时向手机推送一条通知。这是一个**本机运行的 stdio 服务**：不监听端口、不接收外部连接，只按客户端请求向已配置的 Beacon 服务端发 HTTP 请求。

## 提供的工具

| 工具 | 作用 | 需要的凭据 |
| --- | --- | --- |
| `push_notification` | 发送一条通知，支持 Markdown 正文、分组、优先级、有效期、一个 HTTPS 按钮、幂等键、`dry_run` 预演 | 推送密钥 |
| `beacon_status` | 读取服务端能力与协议（协议版本、传输方式、支持字段、按钮上限、幂等支持等） | 设备接收凭据 |
| `beacon_apps` | 只读查看设备列表、发送应用（应用令牌）和存储统计 | 管理令牌 |

没有删除、撤销、清空类工具；推送密钥、接收凭据和管理令牌都不会出现在工具返回结果里。

## 配置

### 环境变量

| 变量 | 说明 |
| --- | --- |
| `BEACON_BASE_URL` | 服务地址，如 `https://push.example.com`，不带结尾斜杠 |
| `BEACON_PUSH_KEY` | 设备推送密钥，`push_notification` 使用 |
| `BEACON_RECEIVE_TOKEN` | 设备接收凭据，仅 `beacon_status` 使用 |
| `BEACON_ADMIN_TOKEN` | 管理令牌，仅 `beacon_apps` 使用 |
| `BEACON_TIMEOUT_MS` | 单次请求超时，默认 10000 |
| `BEACON_MCP_QUIET` | 设为 `1` 时不向 stderr 输出启动摘要 |

也可以改用命令行参数 `--url`、`--key`、`--receive-token`、`--admin-token`、`--timeout`，参数优先于环境变量。

### 注册到 Claude Code

```bash
claude mcp add beacon -s local \
  -e BEACON_BASE_URL=https://push.example.com \
  -e BEACON_PUSH_KEY=<推送密钥> \
  -- node /绝对路径/beacon/mcp/beacon.mjs
```

`-s local` 只写入当前项目；希望所有项目可用时改成 `-s user`。注册后用 `claude mcp list` 确认显示 `✔ Connected`，再在会话中用 `/mcp` 查看工具。

只做推送时，`BEACON_RECEIVE_TOKEN` 与 `BEACON_ADMIN_TOKEN` 可以不配置，对应的工具会返回配置提示而不是报错。

### 其他 MCP 客户端

配置文件中加入等价的 stdio 条目即可，例如：

```json
{
  "mcpServers": {
    "beacon": {
      "command": "node",
      "args": ["/绝对路径/beacon/mcp/beacon.mjs"],
      "env": {
        "BEACON_BASE_URL": "https://push.example.com",
        "BEACON_PUSH_KEY": "<推送密钥>"
      }
    }
  }
}
```

## 手动验证

```bash
BEACON_BASE_URL=https://push.example.com \
BEACON_PUSH_KEY=<推送密钥> \
node mcp/beacon.mjs --help
```

`--help` 只打印用法并退出，适合确认参数解析。运行仓库自带的协议自测：

```bash
cd mcp
npm test
```

自测覆盖换行和 `Content-Length` 两种分帧，以及 `initialize`、`tools/list`、`tools/call` 等交互；服务端会按客户端使用的分帧回复。

## 注意

- 推送成功只表示服务端已保存并排队投递，不代表手机已经收到或展示通知；手机离线时会在恢复连接后补收（默认投递窗口 7 天）。
- 令牌保存在 MCP 客户端配置中，属于本机明文凭据；泄露后请在 App 中撤销对应设备或应用令牌再重新配置。
- 服务端返回 `401` 表示推送密钥无效或已撤销，`429` 表示触发推送限流。
- 凭据只能是 ASCII。若配置里仍是 `<推送密钥>` 这类占位符，或粘贴时混入了中文，`fetch` 会在发出请求前报 `ByteString` 错误；`beacon.mjs` 现在会提前拦截并提示重新复制凭据。
