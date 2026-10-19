#!/usr/bin/env node
// Beacon MCP server: exposes the Beacon push API to MCP clients over stdio.
//
// Credentials come from the environment or CLI flags and never appear in tool
// results. Only the push endpoint and read-only capability/overview endpoints
// are reachable from here; there is no delete, revoke or clear tool.
//
//   BEACON_BASE_URL=https://push.example.com \
//   BEACON_PUSH_KEY=<device push key> \
//   node mcp/beacon.mjs

import { readFileSync } from "node:fs";

const VERSION = "0.1.0";
const SUPPORTED_PROTOCOLS = ["2025-06-18", "2025-03-26", "2024-11-05"];
const DEFAULT_TIMEOUT_MS = 10000;
const MAX_BODY_BYTES = 65536;
const MAX_TITLE_BYTES = 1024;
const MAX_GROUP_BYTES = 128;
const MAX_ACTION_URL_BYTES = 2048;
const MAX_IDEMPOTENCY_BYTES = 128;
const PRIORITY_HELP = "0–1 仅保存，2–3 静默通知，4–7 普通通知，8–10 重要通知";

function usage() {
  process.stderr.write(
    [
      "beacon-mcp — 把 Beacon 推送接口接入 MCP 客户端（stdio）",
      "",
      "用法: node beacon.mjs [选项]",
      "",
      "选项:",
      "  --url <服务地址>      Beacon 服务地址，如 https://push.example.com",
      "  --key <推送密钥>      设备推送密钥，用于 push_notification",
      "  --receive-token <凭据> 设备接收凭据，仅用于 beacon_status",
      "  --admin-token <令牌>   管理令牌，仅用于 beacon_apps",
      "  --timeout <毫秒>       单次请求超时，默认 10000",
      "  --help                 显示本说明",
      "",
      "也可以改用环境变量: BEACON_BASE_URL、BEACON_PUSH_KEY、",
      "BEACON_RECEIVE_TOKEN、BEACON_ADMIN_TOKEN、BEACON_TIMEOUT_MS。",
      "命令行参数优先于环境变量。",
      "",
    ].join("\n"),
  );
}

function parseArgs(argv) {
  const options = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--help" || arg === "-h") {
      usage();
      process.exit(0);
    }
    const next = () => {
      const value = argv[i + 1];
      if (value === undefined || value.startsWith("--")) {
        process.stderr.write(`缺少 ${arg} 的参数值\n`);
        process.exit(2);
      }
      i += 1;
      return value;
    };
    switch (arg) {
      case "--url":
        options.baseUrl = next();
        break;
      case "--key":
        options.pushKey = next();
        break;
      case "--receive-token":
        options.receiveToken = next();
        break;
      case "--admin-token":
        options.adminToken = next();
        break;
      case "--timeout":
        options.timeoutMs = Number(next());
        break;
      default:
        process.stderr.write(`未知参数: ${arg}\n`);
        process.exit(2);
    }
  }
  return options;
}

const flags = parseArgs(process.argv.slice(2));

function normalizeBaseUrl(value) {
  if (!value) return "";
  const trimmed = String(value).trim().replace(/\/+$/, "");
  try {
    const url = new URL(trimmed);
    if (url.protocol !== "https:" && url.protocol !== "http:") return "";
    return trimmed;
  } catch {
    return "";
  }
}

const CONFIG = {
  baseUrl: normalizeBaseUrl(flags.baseUrl ?? process.env.BEACON_BASE_URL),
  pushKey: (flags.pushKey ?? process.env.BEACON_PUSH_KEY ?? "").trim(),
  receiveToken: (flags.receiveToken ?? process.env.BEACON_RECEIVE_TOKEN ?? "").trim(),
  adminToken: (flags.adminToken ?? process.env.BEACON_ADMIN_TOKEN ?? "").trim(),
  timeoutMs: Number.isFinite(flags.timeoutMs)
    ? flags.timeoutMs
    : Number(process.env.BEACON_TIMEOUT_MS ?? DEFAULT_TIMEOUT_MS) || DEFAULT_TIMEOUT_MS,
  quiet: process.env.BEACON_MCP_QUIET === "1",
};

function log(message) {
  if (CONFIG.quiet) return;
  process.stderr.write(`[beacon-mcp] ${message}\n`);
}

function bytes(value) {
  return Buffer.byteLength(String(value), "utf8");
}

function ok(text) {
  return { content: [{ type: "text", text }] };
}

function fail(text) {
  return { content: [{ type: "text", text }], isError: true };
}

function requireBaseUrl() {
  if (!CONFIG.baseUrl) {
    return "未配置服务地址：请设置 BEACON_BASE_URL，或使用 --url <服务地址> 启动本 MCP。";
  }
  return null;
}

function httpHint(status) {
  switch (status) {
    case 400:
      return "消息或参数格式不正确（例如正文为空、文本超长、按钮地址不是 HTTPS）。";
    case 401:
      return "推送密钥无效或已撤销；请在 App 的连接设置中重新复制。";
    case 409:
      return "幂等键已用于不同内容；请为每个新事件使用新的 idempotency_key。";
    case 413:
      return "请求过大。";
    case 429:
      return "推送频率超过限制，稍后重试。";
    case 507:
      return "服务端存储容量达到限制。";
    default:
      return "";
  }
}

async function request(path, { method = "GET", token, body, idempotencyKey } = {}) {
  const missing = requireBaseUrl();
  if (missing) return { transportError: missing };
  const headers = { Accept: "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), CONFIG.timeoutMs);
  try {
    const response = await fetch(`${CONFIG.baseUrl}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
    const text = await response.text();
    let data = null;
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
    return { status: response.status, ok: response.ok, data, text };
  } catch (error) {
    if (error?.name === "AbortError") {
      return { transportError: `请求超时（${CONFIG.timeoutMs}ms）：${CONFIG.baseUrl}${path}` };
    }
    if (/ByteString/i.test(error?.message ?? "")) {
      return {
        transportError:
          "凭据或请求头包含非 ASCII 字符（Bearer 头只能承载 ASCII）。" +
          "通常是配置里保留了占位符或粘贴不完整，请重新复制凭据后再试。",
      };
    }
    return { transportError: `无法连接服务端：${error?.message ?? error}` };
  } finally {
    clearTimeout(timer);
  }
}

function reportFailure(result, action) {
  if (result.transportError) return fail(`${action}失败：${result.transportError}`);
  const code = result.data?.error ? ` ${result.data.error}` : "";
  const hint = httpHint(result.status);
  return fail(
    `${action}失败：HTTP ${result.status}${code}。${hint}`.trimEnd(),
  );
}

async function pushNotification(args) {
  let body = typeof args.body === "string" ? args.body : "";
  if (typeof args.body_file === "string" && args.body_file) {
    try {
      body = readFileSync(args.body_file, "utf8");
    } catch (error) {
      return fail(`无法读取 body_file：${args.body_file}（${error?.message ?? error}）`);
    }
  }
  if (body.trim() === "") {
    return fail("body 不能为空：请通过 body 直接提供正文，或用 body_file 指定本机文件。");
  }
  if (bytes(body) > MAX_BODY_BYTES) {
    return fail(`正文超过上限：${bytes(body)} > ${MAX_BODY_BYTES} UTF-8 字节。`);
  }

  const payload = { body };
  if (typeof args.title === "string" && args.title !== "") {
    if (bytes(args.title) > MAX_TITLE_BYTES) {
      return fail(`title 超过上限：${bytes(args.title)} > ${MAX_TITLE_BYTES} UTF-8 字节。`);
    }
    payload.title = args.title;
  }
  if (typeof args.subtitle === "string" && args.subtitle !== "") {
    payload.subtitle = args.subtitle;
  }
  if (args.priority !== undefined && args.priority !== null) {
    const priority = Number(args.priority);
    if (!Number.isInteger(priority) || priority < 0 || priority > 10) {
      return fail(`priority 必须是 0–10 的整数（${PRIORITY_HELP}）。`);
    }
    payload.priority = priority;
  }
  if (typeof args.group === "string" && args.group !== "") {
    if (bytes(args.group) > MAX_GROUP_BYTES) {
      return fail(`group 超过上限：${bytes(args.group)} > ${MAX_GROUP_BYTES} UTF-8 字节。`);
    }
    payload.group = args.group;
  }
  if (args.ttl !== undefined && args.ttl !== null) {
    const ttl = Number(args.ttl);
    if (!Number.isInteger(ttl) || ttl < 0 || ttl > 604800) {
      return fail("ttl 必须是 0–604800 的整数秒（0 或省略表示七天）。");
    }
    payload.ttl = ttl;
  }
  if (typeof args.url === "string" && args.url !== "") {
    if (bytes(args.url) > MAX_ACTION_URL_BYTES) {
      return fail(`url 超过上限：${bytes(args.url)} > ${MAX_ACTION_URL_BYTES} UTF-8 字节。`);
    }
    let parsed;
    try {
      parsed = new URL(args.url);
    } catch {
      return fail(`url 不是有效地址：${args.url}`);
    }
    if (parsed.protocol !== "https:" || parsed.username || parsed.password) {
      return fail("url 必须是有效的 HTTPS 地址，且不能包含用户名或密码。");
    }
    payload.actions = [{ title: "打开链接", url: args.url }];
  }

  let idempotencyKey;
  if (typeof args.idempotency_key === "string" && args.idempotency_key !== "") {
    idempotencyKey = args.idempotency_key;
    if (bytes(idempotencyKey) > MAX_IDEMPOTENCY_BYTES || /\s/.test(idempotencyKey)) {
      return fail("idempotency_key 最多 128 个字符，且不能包含空白字符。");
    }
  }

  if (args.dry_run === true) {
    return ok(
      [
        "dry_run：未发送。将要发送的请求如下（已隐藏密钥）：",
        `POST ${CONFIG.baseUrl || "<未配置服务地址>"}/api/v1/push`,
        CONFIG.pushKey ? "Authorization: Bearer <已配置的推送密钥>" : "Authorization: Bearer <未配置推送密钥>",
        idempotencyKey ? `Idempotency-Key: ${idempotencyKey}` : "",
        "Content-Type: application/json",
        "",
        JSON.stringify(payload, null, 2),
      ]
        .filter((line) => line !== "")
        .join("\n"),
    );
  }

  if (CONFIG.pushKey && /[^\x20-\x7e]/.test(CONFIG.pushKey)) {
    return fail(
      "推送密钥包含非 ASCII 字符，通常是粘贴了占位符或复制不完整；" +
        "请在 App 的连接设置中重新复制推送密钥并更新配置（Bearer 头只能承载 ASCII）。",
    );
  }

  if (!CONFIG.pushKey) {
    return fail(
      "未配置推送密钥：请设置 BEACON_PUSH_KEY，或使用 --key <推送密钥> 启动本 MCP。",
    );
  }

  const result = await request("/api/v1/push", {
    method: "POST",
    token: CONFIG.pushKey,
    body: payload,
    idempotencyKey,
  });
  if (result.transportError || !result.ok) {
    return reportFailure(result, "推送");
  }
  const data = result.data ?? {};
  return ok(
    [
      "已提交到 Beacon 服务端。",
      `- 消息 id: ${data.id ?? "（响应未返回）"}`,
      `- sequence: ${data.sequence ?? "（响应未返回）"}`,
      data.expires_at_ms ? `- 有效期至: ${new Date(Number(data.expires_at_ms)).toISOString()}` : "",
      data.application_name ? `- 来源应用: ${data.application_name}` : "",
      "",
      "服务端返回成功只代表消息已保存并排队投递，不代表手机已经收到或展示通知。",
      "手机离线时会在恢复连接后补收（默认投递窗口 7 天）。",
    ]
      .filter((line) => line !== "")
      .join("\n"),
  );
}

async function beaconStatus() {
  if (!CONFIG.receiveToken) {
    return fail(
      "未配置接收凭据：beacon_status 需要设备接收凭据。请设置 BEACON_RECEIVE_TOKEN，或用 --receive-token 启动本 MCP。",
    );
  }
  const result = await request("/api/v1/capabilities", { token: CONFIG.receiveToken });
  if (result.transportError || !result.ok) {
    return reportFailure(result, "查询服务端能力");
  }
  const data = result.data ?? {};
  const transports = Array.isArray(data.transports) ? data.transports.join(", ") : "未知";
  const pushApis = Array.isArray(data.push_apis) ? data.push_apis.join(", ") : "未知";
  return ok(
    [
      `服务地址: ${CONFIG.baseUrl}`,
      `服务端 server_id: ${data.server_id ?? "未知"}`,
      `设备 device_id: ${data.device_id ?? "未知"}`,
      `HTTP 协议版本: ${data.protocol ?? "未知"}`,
      `接收传输: ${transports}`,
      `推送接口: ${pushApis}`,
      `支持的消息字段: ${Array.isArray(data.message_fields) ? data.message_fields.join(", ") : "未知"}`,
      `操作按钮上限: ${data.max_message_actions ?? "未知"}`,
      `幂等推送: ${data.push_idempotency === true ? "支持" : "不支持"}`,
      `消息有效期上限: ${data.max_ttl_seconds ?? "未知"} 秒`,
      `分页上限: ${data.max_page_size ?? "未知"}`,
      `投递窗口: ${data.delivery_window_ms ?? "未知"} 毫秒`,
    ].join("\n"),
  );
}

async function beaconApps() {
  if (!CONFIG.adminToken) {
    return fail(
      "未配置管理令牌：beacon_apps 需要管理令牌。请设置 BEACON_ADMIN_TOKEN，或用 --admin-token 启动本 MCP。",
    );
  }
  const result = await request("/api/admin/overview", { token: CONFIG.adminToken });
  if (result.transportError || !result.ok) {
    return reportFailure(result, "查询管理概览");
  }
  const data = result.data ?? {};
  const devices = Array.isArray(data.devices) ? data.devices : [];
  const applications = Array.isArray(data.applications) ? data.applications : [];
  const storage = data.storage ?? {};
  const lines = [
    `服务端版本: ${data.version ?? "未知"}，HTTP 协议 ${data.protocol ?? "?"}，管理协议 ${data.admin_protocol ?? "?"}`,
    `server_id: ${data.server_id ?? "未知"}`,
    "",
    `设备（${devices.length}）:`,
  ];
  if (devices.length === 0) lines.push("- （无）");
  for (const device of devices) {
    lines.push(
      `- ${device.name ?? "未命名"} | ${device.device_id ?? "?"}${device.revoked ? " | 已撤销" : ""}`,
    );
  }
  lines.push("", `发送应用（${applications.length}）:`);
  if (applications.length === 0) lines.push("- （无）");
  for (const application of applications) {
    lines.push(
      `- ${application.name ?? "未命名"} | ${application.id ?? "?"} | 设备 ${application.device_id ?? "?"} | 默认优先级 ${application.default_priority ?? "?"}${application.revoked ? " | 已撤销" : ""}`,
    );
  }
  lines.push(
    "",
    "存储:",
    `- 消息 ${storage.messages ?? "?"}（待投递 ${storage.pending ?? "?"}，已确认 ${storage.confirmed ?? "?"}）`,
    `- 设备 ${storage.devices ?? "?"}`,
  );
  lines.push("", "本工具只读取概览；令牌与推送密钥不会显示。");
  return ok(lines.join("\n"));
}

const TOOLS = [
  {
    name: "push_notification",
    title: "发送 Beacon 通知",
    description:
      "通过已配置的 Beacon 服务端向手机发送一条通知。正文支持 Markdown。服务端返回成功仅表示消息已保存并排队投递，不代表手机已展示通知。适合在长任务结束时提醒用户，不要在循环中高频调用。",
    inputSchema: {
      type: "object",
      additionalProperties: false,
      properties: {
        body: {
          type: "string",
          description: "通知正文，必填（除非提供 body_file）。支持 Markdown，最多 65536 UTF-8 字节。",
        },
        body_file: {
          type: "string",
          description:
            "从本机文件读取正文，适合日志或报告等大段文本，避免全文进入对话。与 body 同时提供时以文件内容为准。",
        },
        title: { type: "string", description: "标题，最多 1024 UTF-8 字节。" },
        subtitle: { type: "string", description: "副标题，最多 1024 UTF-8 字节。" },
        priority: {
          type: "integer",
          minimum: 0,
          maximum: 10,
          description: `优先级，${PRIORITY_HELP}。省略时使用应用默认优先级。`,
        },
        group: {
          type: "string",
          description: "消息分组，用于手机端分类筛选，最多 128 UTF-8 字节。",
        },
        ttl: {
          type: "integer",
          minimum: 0,
          maximum: 604800,
          description: "投递有效期（秒），0–604800；省略或 0 使用七天默认值。",
        },
        url: {
          type: "string",
          description:
            "可选，附加一个 HTTPS 操作按钮，手机上点击后由系统浏览器打开。仅在确有可访问的 HTTPS 地址时提供。",
        },
        idempotency_key: {
          type: "string",
          description:
            "可选幂等键，最多 128 个不含空白的字符。同一密钥与内容在七天内重试不会重复投递；重试请沿用同一个键。",
        },
        dry_run: {
          type: "boolean",
          description: "为 true 时只返回将要发送的请求内容，不实际推送。",
        },
      },
    },
  },
  {
    name: "beacon_status",
    title: "查询 Beacon 服务端能力",
    description:
      "读取已配置服务端的能力与协议信息（协议版本、支持的接收传输、消息字段、按钮上限、幂等等），用于在推送前确认服务端支持哪些字段。需要设备接收凭据。",
    inputSchema: { type: "object", additionalProperties: false, properties: {} },
  },
  {
    name: "beacon_apps",
    title: "查看设备与发送应用",
    description:
      "读取管理概览：设备列表、发送应用（应用令牌）列表和存储统计，用于排查推送失败时确认设备是否已撤销、应用令牌是否存在。需要管理令牌，只读。",
    inputSchema: { type: "object", additionalProperties: false, properties: {} },
  },
];

const HANDLERS = {
  push_notification: pushNotification,
  beacon_status: beaconStatus,
  beacon_apps: beaconApps,
};

// The MCP stdio transport delimits messages with newlines, while earlier
// revisions used LSP-style Content-Length headers. Reply in whichever framing
// the client used, detected from its first message.
let framing = "lines";

function send(message) {
  const payload = Buffer.from(JSON.stringify(message), "utf8");
  if (framing === "headers") {
    process.stdout.write(`Content-Length: ${payload.length}\r\n\r\n`);
    process.stdout.write(payload);
    return;
  }
  process.stdout.write(payload);
  process.stdout.write("\n");
}

async function handle(message) {
  const { id, method, params } = message;
  if (id === undefined || id === null) return; // Notification: nothing to answer.
  switch (method) {
    case "initialize": {
      const requested = params?.protocolVersion;
      const protocolVersion = SUPPORTED_PROTOCOLS.includes(requested)
        ? requested
        : SUPPORTED_PROTOCOLS[0];
      return send({
        jsonrpc: "2.0",
        id,
        result: {
          protocolVersion,
          capabilities: { tools: {} },
          serverInfo: { name: "beacon", title: "Beacon 推送", version: VERSION },
          instructions:
            "用于向用户手机推送 Beacon 通知。发送前先确认用户确实希望收到该提醒；不要在循环中高频推送。",
        },
      });
    }
    case "tools/list":
      return send({ jsonrpc: "2.0", id, result: { tools: TOOLS } });
    case "tools/call": {
      const name = params?.name;
      const handler = HANDLERS[name];
      if (!handler) {
        return send({
          jsonrpc: "2.0",
          id,
          result: fail(`未知工具：${name}`),
        });
      }
      let result;
      try {
        result = await handler(params?.arguments ?? {});
      } catch (error) {
        result = fail(`工具执行失败：${error?.message ?? error}`);
      }
      return send({ jsonrpc: "2.0", id, result });
    }
    case "ping":
      return send({ jsonrpc: "2.0", id, result: {} });
    default:
      return send({
        jsonrpc: "2.0",
        id,
        error: { code: -32601, message: `Method not found: ${method}` },
      });
  }
}

function dispatch(raw) {
  let message;
  try {
    message = JSON.parse(raw);
  } catch {
    return;
  }
  handle(message).catch((error) => log(`处理请求失败: ${error?.message ?? error}`));
}

let buffer = Buffer.alloc(0);
process.stdin.on("data", (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  for (;;) {
    const separator = buffer.indexOf("\r\n\r\n");
    const newline = buffer.indexOf("\n");
    const head = buffer.subarray(0, 16).toString("ascii").toLowerCase();
    if (head.startsWith("content-") && separator >= 0) {
      // Header framing: consume exactly one Content-Length delimited message.
      framing = "headers";
      const match = /content-length:\s*(\d+)/i.exec(buffer.subarray(0, separator).toString("ascii"));
      if (!match) {
        buffer = buffer.subarray(separator + 4);
        continue;
      }
      const start = separator + 4;
      const length = Number(match[1]);
      if (buffer.length < start + length) return;
      const raw = buffer.subarray(start, start + length).toString("utf8");
      buffer = buffer.subarray(start + length);
      dispatch(raw);
      continue;
    }
    if (newline < 0) return;
    const raw = buffer.subarray(0, newline).toString("utf8").trim();
    buffer = buffer.subarray(newline + 1);
    if (raw !== "") dispatch(raw);
  }
});
process.stdin.on("end", () => process.exit(0));
process.on("SIGINT", () => process.exit(0));
process.on("SIGTERM", () => process.exit(0));

log(
  `已启动：服务地址 ${CONFIG.baseUrl || "（未配置）"}，推送密钥 ${CONFIG.pushKey ? "已配置" : "未配置"}，` +
    `接收凭据 ${CONFIG.receiveToken ? "已配置" : "未配置"}，管理令牌 ${CONFIG.adminToken ? "已配置" : "未配置"}`,
);
