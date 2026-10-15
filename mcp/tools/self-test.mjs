#!/usr/bin/env node
// Protocol self-test for beacon.mjs: speaks the MCP stdio transport to a child
// process and asserts the handshake and tool behaviour. No real push is sent;
// only dry_run and validation paths are exercised, so no credentials are needed.
//
//   node mcp/tools/self-test.mjs
//
// Exits 0 when every check passes, 1 otherwise.

import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const server = join(here, "..", "beacon.mjs");
const mode = process.argv[2] === "headers" ? "headers" : "lines";

const child = spawn(process.execPath, [server], {
  stdio: ["pipe", "pipe", "inherit"],
  env: { ...process.env, BEACON_MCP_QUIET: "1", BEACON_BASE_URL: "", BEACON_PUSH_KEY: "", BEACON_RECEIVE_TOKEN: "", BEACON_ADMIN_TOKEN: "" },
});

function frame(message) {
  const payload = Buffer.from(JSON.stringify(message), "utf8");
  if (mode === "headers") {
    return Buffer.concat([Buffer.from(`Content-Length: ${payload.length}\r\n\r\n`), payload]);
  }
  return Buffer.concat([payload, Buffer.from("\n")]);
}

const checks = [];
function check(name, condition, detail) {
  checks.push({ name, condition, detail });
  if (!condition) console.error(`FAIL ${name}${detail ? `: ${detail}` : ""}`);
}

const replies = new Map();
let buffer = Buffer.alloc(0);

function onMessage(message) {
  if (message.id === undefined) return;
  replies.set(message.id, message);
  switch (message.id) {
    case 1:
      child.stdin.write(frame({ jsonrpc: "2.0", id: 2, method: "tools/list" }));
      break;
    case 2:
      child.stdin.write(
        frame({
          jsonrpc: "2.0",
          id: 3,
          method: "tools/call",
          params: { name: "push_notification", arguments: { title: "self test", body: "dry run", group: "MCP", dry_run: true } },
        }),
      );
      break;
    case 3:
      child.stdin.write(
        frame({ jsonrpc: "2.0", id: 4, method: "tools/call", params: { name: "push_notification", arguments: { body: "" } } }),
      );
      break;
    case 4:
      child.stdin.write(
        frame({ jsonrpc: "2.0", id: 5, method: "tools/call", params: { name: "push_notification", arguments: { body: "x", priority: 11 } } }),
      );
      break;
    case 5:
      child.stdin.write(frame({ jsonrpc: "2.0", id: 6, method: "tools/call", params: { name: "no_such_tool", arguments: {} } }));
      break;
    case 6:
      child.stdin.write(frame({ jsonrpc: "2.0", id: 7, method: "tools/call", params: { name: "beacon_status", arguments: {} } }));
      break;
    case 7:
      child.stdin.end();
      setTimeout(finish, 50);
      break;
    default:
      break;
  }
}

function finish() {
  const init = replies.get(1)?.result ?? {};
  check("initialize returns a protocol version", typeof init.protocolVersion === "string", JSON.stringify(init.protocolVersion));
  check("initialize reports server info", init.serverInfo?.name === "beacon", JSON.stringify(init.serverInfo));
  check("initialize declares the tools capability", init.capabilities?.tools !== undefined);

  const tools = replies.get(2)?.result?.tools ?? [];
  const names = tools.map((tool) => tool.name).sort().join(",");
  check("tools/list exposes the three read/push tools", names === "beacon_apps,beacon_status,push_notification", names);
  check("every tool has an input schema", tools.every((tool) => tool.inputSchema?.type === "object"));

  const dryRun = replies.get(3)?.result ?? {};
  check("dry_run succeeds without credentials", dryRun.isError !== true, JSON.stringify(dryRun));
  check("dry_run never prints a credential", /Bearer <[^>]*推送密钥>/.test(dryRun.content?.[0]?.text ?? ""));
  check("dry_run reports the unconfigured key", /Bearer <未配置推送密钥>/.test(dryRun.content?.[0]?.text ?? ""));

  check("empty body is rejected", replies.get(4)?.result?.isError === true);
  check("out-of-range priority is rejected", replies.get(5)?.result?.isError === true);
  check("unknown tool is reported as an error", replies.get(6)?.result?.isError === true);
  check("missing receive credential explains the setting", /BEACON_RECEIVE_TOKEN/.test(replies.get(7)?.result?.content?.[0]?.text ?? ""));

  const failed = checks.filter((entry) => !entry.condition);
  console.log(`${mode} framing: ${checks.length - failed.length}/${checks.length} checks passed`);
  process.exit(failed.length === 0 ? 0 : 1);
}

child.stdout.on("data", (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  for (;;) {
    if (mode === "headers") {
      const separator = buffer.indexOf("\r\n\r\n");
      if (separator < 0) return;
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
      onMessage(JSON.parse(raw));
      continue;
    }
    const newline = buffer.indexOf("\n");
    if (newline < 0) return;
    const raw = buffer.subarray(0, newline).toString("utf8").trim();
    buffer = buffer.subarray(newline + 1);
    if (raw) onMessage(JSON.parse(raw));
  }
});

child.on("exit", (code) => {
  if (code !== 0 && code !== null) {
    console.error(`server exited with code ${code}`);
    process.exit(1);
  }
});

child.stdin.write(
  frame({
    jsonrpc: "2.0",
    id: 1,
    method: "initialize",
    params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "self-test", version: "1" } },
  }),
);
