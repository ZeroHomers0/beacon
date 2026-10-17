// Real TCP/WebSocket frames against workerd, complementing in-runtime tests.
import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdirSync } from "node:fs";
import { resolve } from "node:path";
import { get } from "node:http";
import { gunzipSync } from "node:zlib";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";
import WebSocket from "ws";

if (process.platform === "win32") {
  const temp = resolve(".wrangler/runtime-temp");
  mkdirSync(temp, { recursive: true });
  process.env.TEMP = temp;
  process.env.TMP = temp;
}
const admin = "local-protocol-test-only-admin-token";
const mf = new Miniflare(
  convertV4MiniflareOptions({
    modules: true,
    scriptPath: "dist/index.js",
    compatibilityDate: "2026-10-11",
    durableObjects: {
      BEACON: { className: "BeaconInstance", useSQLite: true },
    },
    bindings: {
      ADMIN_TOKEN: admin,
      BEACON_INSTANCE: "protocol-test",
      HISTORY_RETENTION_DAYS: "30",
    },
  }),
);
let socket;
try {
  const base = await mf.ready;
  async function wireJSON(path, encoding) {
    return new Promise((resolve, reject) => {
      const request = get(new URL(path, base), {
        headers: { "Accept-Encoding": encoding },
      }, (response) => {
        const chunks = [];
        let size = 0;
        response.on("data", (chunk) => {
          size += chunk.length;
          if (size > 1024 * 1024) {
            response.destroy(new Error("response too large"));
          } else chunks.push(chunk);
        });
        response.on("error", reject);
        response.on("end", () => {
          try {
            const body = Buffer.concat(chunks);
            assert.equal(response.headers["content-encoding"], encoding === "gzip" ? "gzip" : undefined);
            const decoded = encoding === "gzip" ? gunzipSync(body) : body;
            resolve({ status: response.statusCode, body: JSON.parse(decoded.toString("utf8")) });
          } catch (error) {
            reject(error);
          }
        });
      });
      request.on("error", reject);
      request.setTimeout(5000, () => request.destroy(new Error("HTTP timeout")));
    });
  }
  for (const encoding of ["gzip", "identity"]) {
    const health = await wireJSON("/healthz", encoding);
    assert.equal(health.status, 200);
    assert.equal(health.body.status, "ok");
    const unauthorized = await wireJSON("/api/admin/overview", encoding);
    assert.equal(unauthorized.status, 401);
    assert.equal(unauthorized.body.error, "unauthorized");
  }
  console.log("HTTP compression passed: one gzip layer, identity and error responses.");
  async function call(path, key, payload) {
    const response = await fetch(new URL(path, base), {
      method: payload ? "POST" : "GET",
      headers: {
        Authorization: `Bearer ${key}`,
        "Content-Type": "application/json",
      },
      body: payload ? JSON.stringify(payload) : undefined,
    });
    assert.ok(response.ok, `HTTP ${response.status}`);
    return response.json();
  }
  const code = await call("/api/admin/binding", admin, {
    name: "protocol test",
  });
  const device = await call("/api/v1/bind", "", {
    binding_code: code.binding_code,
  });
  const stream = new URL("/api/v1/realtime/stream", base);
  stream.protocol = "ws:";
  // Miniflare's local proxy can retain TCP after relaying the close frame.
  // Bound teardown; asserting 1008 still requires receiving that frame.
  socket = new WebSocket(stream, {
    closeTimeout: 1000,
    headers: { Authorization: `Bearer ${device.receive_token}` },
  });
  await once(socket, "open", { signal: AbortSignal.timeout(5000) });
  console.log("TCP WebSocket handshake passed.");
  for (let i = 0; i < 3; i++) {
    const pong = once(socket, "pong", { signal: AbortSignal.timeout(5000) });
    socket.ping(`beacon-${i}`);
    assert.equal((await pong)[0].toString(), `beacon-${i}`);
  }
  console.log("Protocol Ping/Pong passed.");
  const incoming = once(socket, "message", {
    signal: AbortSignal.timeout(5000),
  });
  const message = await call("/api/v1/push", device.push_key, {
    body: "protocol smoke",
  });
  const envelope = JSON.parse((await incoming)[0].toString());
  assert.equal(envelope.message.id, message.id);
  assert.equal(envelope.server_id, device.server_id);
  console.log("Live message delivery passed.");
  const pending = await call(
    "/api/v1/messages?pending=1",
    device.receive_token,
  );
  assert.equal(pending.messages[0].id, message.id);
  await call("/api/v1/messages/ack", device.receive_token, {
    ids: [message.id],
  });
  const closed = once(socket, "close", { signal: AbortSignal.timeout(5000) });
  await call("/api/admin/revoke", admin, { device_id: device.device_id });
  assert.equal((await closed)[0], 1008);
  console.log(
    "Protocol smoke passed: TCP handshake, Ping/Pong, live delivery, HTTP receipt, revocation.",
  );
} finally {
  socket?.terminate();
  await mf.dispose();
}
