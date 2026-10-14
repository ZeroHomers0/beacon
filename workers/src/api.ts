import {
  validateMessageActions,
  PENDING,
  RECEIPT,
  VERSION,
  hash,
  priority,
  pushOptions,
  reject,
  token,
  validID,
} from "./domain";
import { bark, presentation } from "./bark";
import { bearer, body, ids, integer, json, method, text } from "./http";
import type { Repository, Transport } from "./ports";
export class API {
  constructor(
    private store: Repository,
    private transport: Transport,
    private admin: string,
  ) {}
  private dispatch(device: string): void {
    // The message is already committed. A failed realtime attempt must not turn
    // a durable acceptance into an HTTP failure and induce caller duplicates.
    try {
      this.transport.deliver(device);
    } catch {
      /* Persisted outbox + alarm retry. */
    }
  }
  async handle(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    if (path === "/healthz") {
      method(request, "GET");
      return json({ status: "ok", version: VERSION, protocol: "1" });
    }
    if (path.startsWith("/api/admin/"))
      return this.administration(request, path, url);
    if (path === "/api/v1/bind") {
      method(request, "POST");
      const payload = await body(
        request,
        ["binding_code", "device_name"],
        "invalid_or_expired_binding",
      );
      const code = text(payload.binding_code).trim().toLowerCase();
      if (!/^[a-f0-9]{32}$/.test(code))
        reject(400, "invalid_or_expired_binding");
      const receive = token(),
        push = token();
      const [codeHash, receiveHash, pushHash] = await Promise.all([
        hash(code),
        hash(receive),
        hash(push),
      ]);
      await this.transport.arm();
      this.store.admit("bind", Date.now(), 20, 1);
      return json(
        this.store.bind(
          codeHash,
          payload.device_name,
          receiveHash,
          pushHash,
          receive,
          push,
          Date.now(),
        ),
        201,
      );
    }
    if (path === "/api/v1/push") {
      method(request, "POST");
      const credential = bearer(request);
      const payload = await body(
        request,
        ["title", "subtitle", "body", "priority", "group", "ttl", "actions"],
        "invalid_message",
      );
      const credentialHash = await hash(credential);
      await this.transport.arm();
      const sender = this.store.sender(credentialHash);
      const selected = priority(payload.priority, sender.priority);
      const options = pushOptions(
        payload.group,
        payload.ttl,
        request.headers.get("Idempotency-Key") ?? "",
        credentialHash,
        "",
      );
      options.actions = validateMessageActions(payload.actions);
      options.fingerprint = await hash(
        JSON.stringify([
          text(payload.title),
          text(payload.subtitle),
          text(payload.body),
          selected,
          sender.application,
          "",
          options.group,
          options.ttl,
          ...(options.actions.length ? [options.actions] : []),
        ]),
      );
      const m = this.store.save(
        sender,
        text(payload.title),
        text(payload.subtitle),
        text(payload.body),
        selected,
        "",
        Date.now(),
        options,
      );
      this.dispatch(sender.device);
      return json(m);
    }
    if (path === "/api/v1/device/revoke") {
      method(request, "POST");
      if (
        !/^application\/json(?:\s*;|$)/i.test(
          request.headers.get("Content-Type") ?? "",
        )
      )
        reject(415, "json_required");
      const payload = await body(
        request,
        ["server_id", "device_id"],
        "invalid_device",
        1024,
      );
      const credentialHash = await hash(bearer(request));
      await this.transport.arm();
      const device = this.store.receiver(credentialHash, false);
      if (
        payload.server_id !== this.store.serverID ||
        payload.device_id !== device
      )
        reject(401, "unauthorized");
      this.store.revoke(device);
      this.transport.disconnect();
      return json({
        revoked: true,
        server_id: this.store.serverID,
        device_id: device,
      });
    }
    const receivePaths = [
      "/api/v1/capabilities",
      "/api/v1/messages",
      "/api/v1/messages/ack",
      "/api/v1/receipts/status",
      "/api/v1/realtime/config",
      "/api/v1/realtime/stream",
    ];
    if (receivePaths.includes(path)) {
      method(
        request,
        path.endsWith("/ack") || path.endsWith("/status") ? "POST" : "GET",
      );
      const credential = bearer(request);
      const payload =
        request.method === "POST"
          ? await body(
              request,
              ["ids"],
              path.endsWith("/ack") ? "invalid_ack" : "invalid_receipt_query",
            )
          : null;
      const credentialHash = await hash(credential);
      if (path.endsWith("/ack")) await this.transport.arm();
      const device = this.store.receiver(credentialHash);
      const now = Date.now();
      if (path.endsWith("/capabilities"))
        return json({
          protocol: 1,
          server_id: this.store.serverID,
          device_id: device,
          server_time_ms: now,
          transports: ["http", "websocket"],
          push_apis: ["beacon", "bark_text"],
          message_fields: [
            "title",
            "subtitle",
            "body",
            "priority",
            "group",
            "ttl",
            "application_id",
            "application_name",
            "expires_at_ms",
            "actions",
          ],
          push_idempotency: true,
          max_ttl_seconds: 604800,
          max_page_size: 100,
          binding: "one_time_code",
          self_revoke: true,
          delivery_window_ms: PENDING,
          delivery_retention_protocol: 1,
          receipt_status: true,
          reception_control: 1,
          receipt_window_ms: RECEIPT,
          expired_messages: this.store.expiredCount(device),
        });
      if (path.endsWith("/config")) {
        if (url.search || request.url.endsWith("?"))
          reject(400, "invalid_query");
        return json({
          protocol: 2,
          server_id: this.store.serverID,
          device_id: device,
          stream_path: "/api/v1/realtime/stream",
        });
      }
      if (path.endsWith("/stream"))
        return this.transport.stream(request, device, credentialHash);
      if (path.endsWith("/ack")) {
        this.store.ack(device, ids(payload!.ids, "invalid_ack"), now);
        return json({ accepted: true });
      }
      if (path.endsWith("/status"))
        return json({
          statuses: this.store.statuses(
            device,
            ids(payload!.ids, "invalid_receipt_query"),
            now,
          ),
        });
      if (
        [...url.searchParams.keys()].some(
          (key) => !["after", "limit", "pending"].includes(key),
        ) ||
        [...url.searchParams.keys()].some(
          (key) => url.searchParams.getAll(key).length > 1,
        )
      )
        reject(400, "invalid_query");
      const after = integer(
        url.searchParams.get("after"),
        0,
        0,
        Number.MAX_SAFE_INTEGER,
        "invalid_cursor",
      );
      const limit = integer(
        url.searchParams.get("limit"),
        25,
        1,
        100,
        "invalid_limit",
      );
      const pending = url.searchParams.get("pending") ?? "";
      if (pending !== "" && pending !== "1") reject(400, "invalid_pending");
      return json(this.store.page(device, after, limit, pending === "1", now));
    }
    // Bark's response envelope differs from Beacon's; catch at this boundary.
    try {
      const values = await bark(request);
      const credentialHash = await hash(values.device_key ?? "");
      await this.transport.arm();
      // Existing Bark authenticates device push keys; application tokens use /api/v1/push.
      const sender = this.store.sender(credentialHash, false);
      const extension = presentation(values);
      const rawTTL = values.ttl;
      if (rawTTL !== undefined && !/^(0|[1-9][0-9]*)$/.test(rawTTL))
        reject(400, "invalid_message");
      const options = pushOptions(
        values.group,
        rawTTL === undefined ? 0 : Number(rawTTL),
        request.headers.get("Idempotency-Key") ?? "",
        credentialHash,
        "",
      );
      options.fingerprint = await hash(
        JSON.stringify([
          values.title ?? "",
          values.subtitle ?? "",
          extension.body,
          extension.priority,
          sender.application,
          extension.bark,
          options.group,
          options.ttl,
        ]),
      );
      const m = this.store.save(
        sender,
        values.title ?? "",
        values.subtitle ?? "",
        extension.body,
        extension.priority,
        extension.bark,
        Date.now(),
        options,
      );
      this.dispatch(sender.device);
      return json({
        code: 200,
        message: "success",
        timestamp: Math.floor(Date.now() / 1000),
        id: m.id,
        sequence: m.sequence,
      });
    } catch (e) {
      const fault = e as {
        status?: number;
        code?: string;
        headers?: Record<string, string>;
      };
      return json(
        {
          code: fault.status ?? 500,
          message: fault.code ?? "storage_error",
          timestamp: Math.floor(Date.now() / 1000),
        },
        fault.status ?? 500,
        fault.headers,
      );
    }
  }
  private async administration(
    request: Request,
    path: string,
    url: URL,
  ): Promise<Response> {
    const actual = await hash(request.headers.get("Authorization") ?? "");
    const expected = await hash(`Bearer ${this.admin}`);
    // Compare fixed-length digests without an early-exit string comparison.
    let difference = 0;
    for (let i = 0; i < expected.length; i++)
      difference |= actual.charCodeAt(i) ^ expected.charCodeAt(i);
    if (!this.admin || difference) reject(401, "unauthorized");
    if (url.search) reject(400, "invalid_query");
    if (path === "/api/admin/overview") {
      method(request, "GET");
      return json({
        devices: this.store.devices(),
        applications: this.store.applications(),
        storage: this.store.stats(),
        transport: "websocket",
        server_id: this.store.serverID,
        version: VERSION,
        protocol: 1,
        admin_protocol: 1,
      });
    }
    const routes: Record<string, string[]> = {
      "/api/admin/messages/clear": [],
      "/api/admin/devices/delete": ["device_id"],
      "/api/admin/binding": ["name"],
      "/api/admin/revoke": ["device_id"],
      "/api/admin/test": ["device_id", "title", "subtitle", "body", "priority"],
      "/api/admin/applications": ["device_id", "name", "default_priority"],
      "/api/admin/applications/revoke": ["id"],
      "/api/admin/applications/delete": ["id"],
    };
    if (!routes[path]) reject(404, "not_found");
    method(request, "POST");
    const payload = await body(request, routes[path]);
    const credential = token();
    const credentialHash = await hash(
      path.endsWith("/binding") ? credential.slice(0, 32) : credential,
    );
    await this.transport.arm();
    if (path === "/api/admin/messages/clear")
      return json({ deleted: this.store.clearMessages() });
    if (path === "/api/admin/devices/delete") {
      if (!validID(payload.device_id)) reject(400, "invalid_device");
      return json({
        deleted: this.store.clearMessages(payload.device_id, true),
        device_deleted: true,
      });
    }
    if (path.endsWith("/binding"))
      return json(
        this.store.createBinding(
          payload.name,
          credentialHash,
          credential.slice(0, 32),
          Date.now(),
        ),
        201,
      );
    if (path === "/api/admin/applications/revoke") {
      if (!validID(payload.id)) reject(400, "invalid_application");
      this.store.revokeApplication(payload.id);
      return json({ revoked: true });
    }
    if (path === "/api/admin/applications/delete") {
      if (!validID(payload.id)) reject(400, "invalid_application");
      return json({
        deleted: this.store.deleteApplication(payload.id),
        application_deleted: true,
      });
    }
    if (!validID(payload.device_id)) reject(400, "invalid_device");
    if (path.endsWith("/revoke")) {
      this.store.revoke(payload.device_id);
      this.transport.disconnect();
      return json({ revoked: true });
    }
    if (path.endsWith("/applications"))
      return json(
        this.store.createApplication(
          payload.device_id,
          payload.name,
          priority(payload.default_priority, 5),
          credentialHash,
          credential,
        ),
        201,
      );
    this.store.active(payload.device_id);
    const selected = priority(payload.priority, 5);
    const m = this.store.save(
      { device: payload.device_id, application: "", priority: selected },
      text(payload.title),
      text(payload.subtitle),
      text(payload.body),
      selected,
      "",
      Date.now(),
    );
    this.dispatch(payload.device_id);
    return json(m, 201);
  }
}
