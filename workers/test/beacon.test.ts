import { env } from "cloudflare:workers";
import {
  evictDurableObject,
  reset,
  runInDurableObject,
  runDurableObjectAlarm,
} from "cloudflare:test";
import { afterEach, describe, expect, it } from "vitest";
import worker, { BeaconInstance, Env } from "../src/index";
import { DAY, GRACE, PENDING, RECEIPT } from "../src/domain";
import { MessageStore } from "../src/store/messages";

const bindings = env as unknown as Env;
const admin = "test-only-admin-token-32-characters";
function stub() {
  return bindings.BEACON.get(
    bindings.BEACON.idFromName(bindings.BEACON_INSTANCE),
  );
}
async function call(
  path: string,
  credential = admin,
  payload?: unknown,
  extra: Record<string, string> = {},
) {
  return worker.fetch(
    new Request(`https://beacon.test${path}`, {
      method: payload === undefined ? "GET" : "POST",
      headers: {
        Authorization: `Bearer ${credential}`,
        ...(payload === undefined
          ? {}
          : { "Content-Type": "application/json" }),
        ...extra,
      },
      body: payload === undefined ? undefined : JSON.stringify(payload),
    }),
    bindings,
  );
}
async function bind(label = "phone") {
  const codeResponse = await call("/api/admin/binding", admin, { name: label });
  expect(codeResponse.status).toBe(201);
  const code = await codeResponse.json<{ binding_code: string }>();
  const response = await call("/api/v1/bind", "", {
    binding_code: code.binding_code,
    device_name: label,
  });
  expect(response.status).toBe(201);
  return response.json<{
    device_id: string;
    server_id: string;
    receive_token: string;
    push_key: string;
  }>();
}
async function push(key: string, body = "hello") {
  const response = await call("/api/v1/push", key, {
    title: "title",
    subtitle: "subtitle",
    body,
  });
  expect(response.status).toBe(200);
  return response.json<{ id: string; sequence: number; body: string }>();
}
async function rows(sql: string, ...values: SqlStorageValue[]) {
  return runInDurableObject(stub(), (_instance: BeaconInstance, state) => [
    ...state.storage.sql.exec(sql, ...values),
  ]);
}
async function stream(key: string) {
  const response = await call("/api/v1/realtime/stream", key, undefined, {
    Upgrade: "websocket",
  });
  expect(response.status).toBe(101);
  const ws = response.webSocket!;
  ws.accept();
  return ws;
}
function nextMessage(ws: WebSocket): Promise<Record<string, any>> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(
      () => reject(new Error("missing realtime message")),
      5000,
    );
    ws.addEventListener(
      "message",
      (event) => {
        clearTimeout(timeout);
        resolve(JSON.parse(String(event.data)));
      },
      { once: true },
    );
  });
}
afterEach(async () => {
  await reset();
});

describe("Beacon protocol and durable delivery", () => {
  it("binds once, stores only hashes and exposes compatible capabilities", async () => {
    const response = await call("/api/admin/binding", admin, { name: "phone" });
    const code = await response.json<{ binding_code: string }>();
    const requests = await Promise.all(
      [1, 2].map(() =>
        call("/api/v1/bind", "", {
          binding_code: code.binding_code,
          device_name: "phone",
        }),
      ),
    );
    expect(requests.map((r) => r.status).sort()).toEqual([201, 400]);
    const device = await requests
      .find((r) => r.status === 201)!
      .json<{ receive_token: string; push_key: string; device_id: string }>();
    const stored = JSON.stringify(await rows("SELECT * FROM devices"));
    expect(stored).not.toContain(device.receive_token);
    expect(stored).not.toContain(device.push_key);
    const capability = await (
      await call("/api/v1/capabilities", device.receive_token)
    ).json();
    expect(capability).toMatchObject({
      protocol: 1,
      device_id: device.device_id,
      delivery_retention_protocol: 1,
      receipt_status: true,
      transports: ["http", "websocket"],
    });
    expect(
      await (
        await call("/api/v1/realtime/config", device.receive_token)
      ).json(),
    ).toMatchObject({ protocol: 2, stream_path: "/api/v1/realtime/stream" });
  });

  it("queues offline delivery, survives eviction, paginates and acknowledges idempotently", async () => {
    const device = await bind();
    const first = await push(device.push_key),
      second = await push(device.push_key);
    expect(await rows("SELECT * FROM publish_outbox")).toHaveLength(2);
    expect(
      await runInDurableObject(stub(), (_instance, state) =>
        state.storage.getAlarm(),
      ),
    ).not.toBeNull();
    await evictDurableObject(stub());
    const page = await (
      await call("/api/v1/messages?pending=1&limit=1", device.receive_token)
    ).json<{
      messages: unknown[];
      next_cursor: number;
      has_more: boolean;
      server_id: string;
    }>();
    expect(page).toMatchObject({
      server_id: device.server_id,
      next_cursor: first.sequence,
      has_more: true,
    });
    const rest = await (
      await call(
        `/api/v1/messages?after=${page.next_cursor}&limit=1`,
        device.receive_token,
      )
    ).json<{ messages: { id: string }[] }>();
    expect(rest.messages[0].id).toBe(second.id);
    for (let i = 0; i < 2; i++)
      expect(
        (
          await call("/api/v1/messages/ack", device.receive_token, {
            ids: [first.id],
          })
        ).status,
      ).toBe(200);
    expect(await rows("SELECT * FROM publish_outbox")).toHaveLength(1);
    const pending = await (
      await call("/api/v1/messages?pending=1", device.receive_token)
    ).json<{ messages: { id: string }[] }>();
    expect(pending.messages.map((m) => m.id)).toEqual([second.id]);
  });

  it("rolls back a mixed acknowledgement batch and isolates devices", async () => {
    const device = await bind(),
      other = await bind("other");
    const own = await push(device.push_key),
      foreign = await push(other.push_key);
    expect(
      (
        await call("/api/v1/messages/ack", device.receive_token, {
          ids: [own.id, foreign.id],
        })
      ).status,
    ).toBe(400);
    expect(
      (await rows("SELECT acked FROM messages WHERE id=?", own.id))[0].acked,
    ).toBe(0);
    expect(await rows("SELECT * FROM publish_outbox")).toHaveLength(2);
    const statuses = await (
      await call("/api/v1/receipts/status", device.receive_token, {
        ids: [own.id, foreign.id],
      })
    ).json();
    expect(statuses).toEqual({
      statuses: { [own.id]: "available", [foreign.id]: "missing" },
    });
  });

  it("delivers to hibernated sockets without treating socket writes as receipts", async () => {
    const device = await bind();
    const ws = await stream(device.receive_token);
    await evictDurableObject(stub(), { webSockets: "hibernate" });
    const pending = nextMessage(ws);
    const m = await push(device.push_key);
    expect(await pending).toMatchObject({
      protocol: 1,
      server_id: device.server_id,
      message: { id: m.id, subtitle: "subtitle" },
    });
    expect(await rows("SELECT * FROM publish_outbox")).toHaveLength(0);
    expect(
      (await rows("SELECT acked FROM messages WHERE id=?", m.id))[0].acked,
    ).toBe(0);
    ws.close();
  });

  it("recovers the commit-before-dispatch crash window from its alarm", async () => {
    const device = await bind();
    const ws = await stream(device.receive_token);
    const pending = nextMessage(ws);
    const m = await runInDurableObject(
      stub(),
      async (instance: BeaconInstance, state) => {
        await instance.arm();
        const store = new MessageStore(state.storage);
        return store.save(
          { device: device.device_id, application: "", priority: 5 },
          "",
          "",
          "recover me",
          5,
          "",
          Date.now(),
        );
      },
    );
    await evictDurableObject(stub(), { webSockets: "hibernate" });
    expect(await runDurableObjectAlarm(stub())).toBe(true);
    expect(await pending).toMatchObject({
      message: { id: m.id, body: "recover me" },
    });
    expect(await rows("SELECT * FROM publish_outbox")).toHaveLength(0);
    ws.close();
  });

  it("retries offline tasks with backoff and returns to hourly maintenance when empty", async () => {
    const device = await bind();
    await push(device.push_key);
    const before = Number(
      (await rows("SELECT next_attempt FROM publish_outbox"))[0].next_attempt,
    );
    await rows("UPDATE publish_outbox SET next_attempt=0");
    await runDurableObjectAlarm(stub());
    const task = (await rows("SELECT * FROM publish_outbox"))[0];
    expect(task.attempts).toBe(2);
    expect(Number(task.next_attempt)).toBeGreaterThan(before);
    await rows("DELETE FROM publish_outbox");
    await runDurableObjectAlarm(stub());
    const next = await runInDurableObject(stub(), (_instance, state) =>
      state.storage.getAlarm(),
    );
    expect(next! - Date.now()).toBeGreaterThan(50 * 60_000);
  });

  it("revokes hibernated connections immediately and permits lost-response self-revoke retries", async () => {
    const device = await bind();
    const ws = await stream(device.receive_token);
    await evictDurableObject(stub(), { webSockets: "hibernate" });
    const closed = new Promise<number>((resolve) =>
      ws.addEventListener("close", (event) => resolve(event.code), {
        once: true,
      }),
    );
    const payload = {
      server_id: device.server_id,
      device_id: device.device_id,
    };
    expect(
      (await call("/api/v1/device/revoke", device.receive_token, payload))
        .status,
    ).toBe(200);
    expect(await closed).toBe(1008);
    expect(
      (await call("/api/v1/device/revoke", device.receive_token, payload))
        .status,
    ).toBe(200);
    expect((await call("/api/v1/messages", device.receive_token)).status).toBe(
      401,
    );
    expect(
      (await call("/api/v1/push", device.push_key, { body: "denied" })).status,
    ).toBe(401);
  });

  it("restores durable disconnect work after interruption", async () => {
    const device = await bind();
    const ws = await stream(device.receive_token);
    const closed = new Promise<number>((resolve) =>
      ws.addEventListener("close", (event) => resolve(event.code), {
        once: true,
      }),
    );
    await runInDurableObject(
      stub(),
      async (instance: BeaconInstance, state) => {
        await instance.arm();
        new MessageStore(state.storage).revoke(device.device_id);
      },
    );
    await evictDurableObject(stub(), { webSockets: "hibernate" });
    await runDurableObjectAlarm(stub());
    expect(await closed).toBe(1008);
    expect(await rows("SELECT * FROM session_disconnects")).toHaveLength(0);
  });

  it("supports application defaults and denies revoked sending credentials", async () => {
    const device = await bind();
    const appResponse = await call("/api/admin/applications", admin, {
      device_id: device.device_id,
      name: "CI",
      default_priority: 8,
    });
    expect(appResponse.status).toBe(201);
    const app = await appResponse.json<{ id: string; token: string }>();
    const m = await (
      await call("/api/v1/push", app.token, { body: "build" })
    ).json();
    expect(m).toMatchObject({ priority: 8, application_id: app.id });
    expect(
      (await call("/api/admin/applications/revoke", admin, { id: app.id }))
        .status,
    ).toBe(200);
    expect(
      (await call("/api/v1/push", app.token, { body: "build" })).status,
    ).toBe(401);
    const overview = await (await call("/api/admin/overview")).json();
    expect(overview).toMatchObject({
      admin_protocol: 1,
      protocol: 1,
      applications: [{ id: app.id, revoked: true }],
    });
    expect(JSON.stringify(overview)).not.toContain(app.token);
  });

  it("expires pending bodies in bounded batches and keeps retry receipts", async () => {
    const device = await bind();
    const m = await push(device.push_key);
    const now = Date.now();
    await rows(
      "UPDATE messages SET created=? WHERE id=?",
      now - PENDING - 1000,
      m.id,
    );
    await rows(
      "UPDATE metadata SET value=? WHERE key='maintenance_due'",
      String(now - 1),
    );
    await runDurableObjectAlarm(stub());
    expect(await rows("SELECT * FROM messages")).toHaveLength(0);
    expect(await rows("SELECT * FROM publish_outbox")).toHaveLength(0);
    const receipt = (await rows("SELECT * FROM expired_receipts"))[0];
    expect(Number(receipt.expires_at)).toBe(now + RECEIPT + GRACE - 1000);
    expect(
      (
        await call("/api/v1/messages/ack", device.receive_token, {
          ids: [m.id],
        })
      ).status,
    ).toBe(200);
    expect(
      await (await call("/api/v1/capabilities", device.receive_token)).json(),
    ).toMatchObject({ expired_messages: 1 });
    await rows("UPDATE expired_receipts SET expires_at=?", Date.now() - 1);
    expect(
      (
        await call("/api/v1/messages/ack", device.receive_token, {
          ids: [m.id],
        })
      ).status,
    ).toBe(400);
  });

  it("retains confirmed tombstones after history cleanup and preserves pending bodies", async () => {
    const device = await bind();
    const confirmed = await push(device.push_key),
      pending = await push(device.push_key);
    await call("/api/v1/messages/ack", device.receive_token, {
      ids: [confirmed.id],
    });
    await rows(
      "UPDATE messages SET created=? WHERE id=?",
      Date.now() - 31 * DAY,
      confirmed.id,
    );
    await rows("UPDATE metadata SET value='0' WHERE key='maintenance_due'");
    await runDurableObjectAlarm(stub());
    expect((await rows("SELECT id FROM messages")).map((r) => r.id)).toEqual([
      pending.id,
    ]);
    expect(await rows("SELECT id FROM confirmed_receipts")).toEqual([
      { id: confirmed.id },
    ]);
    expect(
      (
        await call("/api/v1/messages/ack", device.receive_token, {
          ids: [confirmed.id],
        })
      ).status,
    ).toBe(200);
  });

  it("enforces per-device capacity atomically", async () => {
    const device = await bind();
    await rows(
      `WITH RECURSIVE counter(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM counter WHERE x<10000)
      INSERT INTO messages(id,device,title,subtitle,body,created,priority) SELECT printf('%064x',x),?,'','','x',?,5 FROM counter`,
      device.device_id,
      Date.now(),
    );
    expect(
      (await call("/api/v1/push", device.push_key, { body: "over capacity" }))
        .status,
    ).toBe(507);
    expect(await rows("SELECT * FROM publish_outbox")).toHaveLength(0);
    expect((await rows("SELECT count(*) AS n FROM messages"))[0].n).toBe(10000);
  });

  it("keeps quota counters consistent through ack, rollback and deletion", async () => {
    const device = await bind();
    const m = await push(device.push_key, "中");
    expect(
      (await rows("SELECT pending_count,pending_bytes FROM devices"))[0],
    ).toEqual({ pending_count: 1, pending_bytes: 16 });
    await call("/api/v1/messages/ack", device.receive_token, {
      ids: [m.id, "a".repeat(64)],
    });
    expect(
      (await rows("SELECT pending_count FROM devices"))[0].pending_count,
    ).toBe(1);
    await call("/api/v1/messages/ack", device.receive_token, { ids: [m.id] });
    expect(
      (await rows("SELECT pending_count,pending_bytes FROM devices"))[0],
    ).toEqual({ pending_count: 0, pending_bytes: 0 });
    await rows("DELETE FROM messages");
    expect((await rows("SELECT messages FROM usage"))[0].messages).toBe(0);
  });

  it("reports committed acceptance when immediate dispatch fails and preserves recovery", async () => {
    const device = await bind();
    await runInDurableObject(stub(), (instance: BeaconInstance) => {
      instance.deliver = () => {
        throw new Error("injected transport failure");
      };
    });
    const m = await push(device.push_key);
    expect((await rows("SELECT id FROM messages"))[0].id).toBe(m.id);
    expect(await rows("SELECT * FROM publish_outbox")).toHaveLength(1);
    await evictDurableObject(stub());
    await runDurableObjectAlarm(stub());
    expect(
      (await rows("SELECT attempts FROM publish_outbox"))[0].attempts,
    ).toBe(1);
  });

  it("processes large expired backlogs in bounded batches", async () => {
    const device = await bind();
    await rows(
      `WITH RECURSIVE counter(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM counter WHERE x<101)
      INSERT INTO messages(id,device,title,subtitle,body,created,priority) SELECT printf('%064x',x),?,'','','x',?,5 FROM counter`,
      device.device_id,
      Date.now() - PENDING - 1000,
    );
    await rows("UPDATE metadata SET value='0' WHERE key='maintenance_due'");
    await runDurableObjectAlarm(stub());
    expect(await rows("SELECT id FROM messages")).toHaveLength(1);
    const due = (
      await rows("SELECT value FROM metadata WHERE key='maintenance_due'")
    )[0].value;
    expect(Number(due) - Date.now()).toBeLessThanOrEqual(1000);
    await rows("UPDATE metadata SET value='0' WHERE key='maintenance_due'");
    await runDurableObjectAlarm(stub());
    expect(await rows("SELECT id FROM messages")).toHaveLength(0);
    expect(
      (
        await rows(
          "SELECT pending_count,pending_bytes,expired_messages FROM devices",
        )
      )[0],
    ).toEqual({ pending_count: 0, pending_bytes: 0, expired_messages: 101 });
  });

  it("does not reset binding admission counters on eviction", async () => {
    await bind();
    await rows(
      "INSERT INTO rate_buckets VALUES('bind',?,0) ON CONFLICT(key) DO UPDATE SET at=excluded.at,tokens=0",
      Date.now() + 100000,
    );
    await evictDurableObject(stub());
    const response = await call("/api/v1/bind", "", {
      binding_code: "a".repeat(32),
    });
    expect(response.status).toBe(429);
    expect((await response.json<{ error: string }>()).error).toBe(
      "binding_rate_limited",
    );
  });

  it("rejects malformed/duplicate JSON, oversized UTF-8 and unknown fields", async () => {
    const device = await bind();
    for (const raw of [
      '{"body":"first","body":"second"}',
      '{"body":"\\ud800"}',
      '{"body":"ok","unknown":1}',
      '{"body":"ok"} trailing',
      '{"body":42}',
    ]) {
      const response = await worker.fetch(
        new Request("https://beacon.test/api/v1/push", {
          method: "POST",
          headers: { Authorization: `Bearer ${device.push_key}` },
          body: raw,
        }),
        bindings,
      );
      expect(response.status).toBe(400);
    }
    expect(
      (
        await call("/api/v1/push", device.push_key, {
          body: "中".repeat(22000),
        })
      ).status,
    ).toBe(400);
    expect(
      (
        await call("/api/v1/push", device.push_key, {
          body: "x".repeat(600000),
        })
      ).status,
    ).toBe(413);
    expect(await rows("SELECT * FROM messages")).toHaveLength(0);
  });

  it("isolates administration and rejects query/origin websocket credentials", async () => {
    const device = await bind();
    expect(
      (await call("/api/admin/overview", device.receive_token)).status,
    ).toBe(401);
    expect((await call("/api/admin/overview?token=secret")).status).toBe(400);
    expect(
      (await call("/api/v1/realtime/config?unexpected=1", device.receive_token))
        .status,
    ).toBe(400);
    expect(
      (
        await call(
          "/api/v1/realtime/stream?token=secret",
          device.receive_token,
          undefined,
          { Upgrade: "websocket" },
        )
      ).status,
    ).toBe(400);
    expect(
      (
        await call("/api/v1/realtime/stream", device.receive_token, undefined, {
          Upgrade: "websocket",
          Origin: "https://other.test",
        })
      ).status,
    ).toBe(400);
    expect(
      (await call("/api/v1/messages?after=-1", device.receive_token)).status,
    ).toBe(400);
    expect(
      (await call("/api/v1/messages?limit=101", device.receive_token)).status,
    ).toBe(400);
  });

  it("bounds sockets and closes client application messages on the receive-only stream", async () => {
    const device = await bind();
    const sockets: WebSocket[] = [];
    for (let i = 0; i < 4; i++)
      sockets.push(await stream(device.receive_token));
    expect(
      (
        await call("/api/v1/realtime/stream", device.receive_token, undefined, {
          Upgrade: "websocket",
        })
      ).status,
    ).toBe(503);
    const closed = new Promise<number>((resolve) =>
      sockets[0].addEventListener("close", (event) => resolve(event.code), {
        once: true,
      }),
    );
    sockets[0].send("unexpected app ping");
    expect(await closed).toBe(1008);
    sockets.slice(1).forEach((ws) => ws.close());
  });

  it("preserves Bark encoded separators, silent priority and encrypted presentation", async () => {
    const device = await bind();
    const response = await call(
      `/${device.push_key}/title%2Fpart/subtitle/body%2Fpart?sound=none&image=https%3A%2F%2Fexample.com%2Fi.png`,
    );
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({
      code: 200,
      message: "success",
    });
    const page = await (
      await call("/api/v1/messages", device.receive_token)
    ).json<{ messages: any[] }>();
    expect(page.messages[0]).toMatchObject({
      title: "title/part",
      subtitle: "subtitle",
      body: "body/part",
      priority: 2,
    });
    expect(JSON.parse(page.messages[0].bark)).toMatchObject({
      sound: "none",
      image: "https://example.com/i.png",
    });
    expect(
      (
        await call("/push", admin, {
          device_key: device.push_key,
          ciphertext: btoa("x".repeat(16)),
          iv: "1234567890123456",
        })
      ).status,
    ).toBe(200);
    expect(
      (await call(`/${device.push_key}/body?sound=none&sound=bell`)).status,
    ).toBe(400);
    expect(
      (await call(`/${device.push_key}/body?image=http%3A%2F%2Fexample.com`))
        .status,
    ).toBe(400);
  });

  it("fails closed without a deployment secret and uses a stable instance independent of host", async () => {
    expect(
      (
        await worker.fetch(new Request("https://beacon.test/healthz"), {
          ...bindings,
          ADMIN_TOKEN: "",
        })
      ).status,
    ).toBe(503);
    const device = await bind();
    const response = await worker.fetch(
      new Request("https://different.test/api/v1/capabilities", {
        headers: { Authorization: `Bearer ${device.receive_token}` },
      }),
      bindings,
    );
    expect(await response.json()).toMatchObject({
      server_id: device.server_id,
    });
  });
});
