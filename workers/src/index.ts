import { DurableObject } from "cloudflare:workers";
import { API } from "./api";
import { Attachment, MAINTENANCE, message, reject } from "./domain";
import { error, json } from "./http";
import { MessageStore } from "./store/messages";
import { migrate } from "./store/schema";
import type { Transport } from "./ports";

export interface Env {
  BEACON: DurableObjectNamespace<BeaconInstance>;
  BEACON_INSTANCE: string;
  ADMIN_TOKEN: string;
  HISTORY_RETENTION_DAYS: string;
}
export class BeaconInstance extends DurableObject<Env> implements Transport {
  private store: MessageStore;
  private api: API;
  private retention: number;
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.retention = Number(env.HISTORY_RETENTION_DAYS ?? "1");
    if (
      !Number.isInteger(this.retention) ||
      this.retention < 0 ||
      this.retention > 3650
    )
      throw new Error("invalid retention configuration");
    this.retention = Math.min(30, Math.max(1, this.retention));
    this.store = new MessageStore(ctx.storage);
    this.api = new API(this.store, this, env.ADMIN_TOKEN);
    ctx.blockConcurrencyWhile(async () => {
      migrate(ctx.storage);
      if (
        !this.store.first(
          "SELECT value FROM metadata WHERE key='maintenance_due'",
        )
      ) {
        this.store.rows(
          "INSERT INTO metadata VALUES('maintenance_due',?)",
          String(Date.now() + MAINTENANCE),
        );
      }
      await this.schedule();
    });
  }
  async fetch(request: Request): Promise<Response> {
    try {
      return await this.api.handle(request);
    } catch (e) {
      return error(e);
    }
  }
  async arm(): Promise<void> {
    // An alarm is committed before any state mutation. A crash after SQL commit
    // but before dispatch therefore always has a durable recovery wakeup.
    await this.ctx.storage.transaction(async (storage) => {
      const current = await storage.getAlarm();
      const recovery = Date.now() + 5000;
      if (current === null || current > recovery)
        await storage.setAlarm(recovery);
    });
  }
  private async schedule(): Promise<void> {
    await this.ctx.storage.transaction(async (storage) => {
      const next = this.store.nextWake(Date.now());
      const current = await storage.getAlarm();
      if (current === null || current > next) await storage.setAlarm(next);
    });
  }
  stream(request: Request, device: string, hash: string): Response {
    const url = new URL(request.url);
    if (
      url.search ||
      request.url.endsWith("?") ||
      request.headers.has("Origin")
    )
      reject(400, "invalid_stream_request");
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket")
      reject(426, "websocket_required");
    if (
      this.ctx.getWebSockets().filter((ws) => ws.readyState === WebSocket.OPEN)
        .length >= 256 ||
      this.ctx
        .getWebSockets(device)
        .filter((ws) => ws.readyState === WebSocket.OPEN).length >= 4
    )
      reject(503, "stream_capacity");
    const pair = new WebSocketPair();
    pair[1].serializeAttachment({ device, hash } satisfies Attachment);
    this.ctx.acceptWebSocket(pair[1], [device]);
    // Client performs HTTP catch-up; do not send before the connection handshake.
    return new Response(null, { status: 101, webSocket: pair[0] });
  }
  deliver(device?: string): void {
    const now = Date.now();
    for (const task of this.store.due(now, device)) {
      let delivered = false;
      const payload = JSON.stringify({
        protocol: 1,
        server_id: this.store.serverID,
        message: message(task),
      });
      for (const ws of this.ctx.getWebSockets(String(task.device))) {
        if (ws.readyState !== WebSocket.OPEN) continue;
        const attachment = ws.deserializeAttachment() as Attachment;
        if (!attachment || !this.store.authorized(attachment)) {
          ws.close(1008, "unauthorized");
          continue;
        }
        try {
          ws.send(payload);
          delivered = true;
        } catch {
          ws.close(1011, "delivery_failed");
        }
      }
      // Socket success retires publication only; it never acknowledges the message.
      this.store.complete(
        Number(task.seq),
        delivered,
        Number(task.attempts),
        now,
      );
    }
  }
  disconnect(): void {
    for (const row of this.store.rows(
      "SELECT device FROM session_disconnects LIMIT 100",
    )) {
      for (const ws of this.ctx.getWebSockets(String(row.device))) {
        if (ws.readyState === WebSocket.OPEN) ws.close(1008, "unauthorized");
      }
      this.store.rows(
        "DELETE FROM session_disconnects WHERE device=?",
        row.device,
      );
    }
  }
  async alarm(): Promise<void> {
    // Only local, bounded SQL/socket work here. Block new writes while replacing
    // the alarm so a concurrent arm() cannot be overwritten by a stale deadline.
    await this.ctx.blockConcurrencyWhile(async () => {
      try {
        this.disconnect();
        this.deliver();
        const due = Number(
          this.store.first(
            "SELECT value FROM metadata WHERE key='maintenance_due'",
          )!.value,
        );
        if (due <= Date.now()) this.store.maintain(Date.now(), this.retention);
        await this.ctx.storage.setAlarm(this.store.nextWake(Date.now()));
      } catch (e) {
        // Persist a new wakeup even if the platform's finite retries are exhausted.
        await this.ctx.storage.setAlarm(Date.now() + 5000);
        throw e;
      }
    });
  }
  webSocketMessage(ws: WebSocket): void {
    ws.close(1008, "receive_only");
  }
  webSocketClose(ws: WebSocket, code: number): void {
    // Abnormal/no-status codes are diagnostic values, never valid wire codes.
    ws.close([1005, 1006, 1015].includes(code) ? 1000 : code);
  }
  webSocketError(ws: WebSocket): void {
    ws.close(1011, "transport_error");
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (!env.ADMIN_TOKEN || env.ADMIN_TOKEN.length < 32 || !env.BEACON_INSTANCE)
      return json({ error: "server_not_configured" }, 503);
    const id = env.BEACON.idFromName(env.BEACON_INSTANCE);
    // Never derive the object ID from a path, hostname or caller-supplied header.
    return env.BEACON.get(id).fetch(request);
  },
} satisfies ExportedHandler<Env>;
