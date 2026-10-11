import { Attachment, Row, Sender, name, reject, token } from "../domain";

export class IdentityStore {
  constructor(protected storage: DurableObjectStorage) {}
  rows(query: string, ...values: SqlStorageValue[]): Row[] {
    return [...this.storage.sql.exec(query, ...values)];
  }
  first(query: string, ...values: SqlStorageValue[]): Row | undefined {
    return this.rows(query, ...values)[0];
  }
  get serverID(): string {
    return String(
      this.first("SELECT value FROM metadata WHERE key='server_id'")!.value,
    );
  }
  receiver(hash: string, active = true): string {
    const row = this.first(
      `SELECT id FROM devices WHERE receive_hash=?${active ? " AND revoked=0" : ""}`,
      hash,
    );
    if (!row) reject(401, "unauthorized");
    return String(row.id);
  }
  sender(hash: string, applications = true): Sender {
    const device = this.first(
      "SELECT id FROM devices WHERE push_hash=? AND revoked=0",
      hash,
    );
    if (device)
      return { device: String(device.id), application: "", priority: 5 };
    const app =
      applications &&
      this.first(
        `SELECT a.id,a.device,a.default_priority FROM applications a JOIN devices d ON d.id=a.device
      WHERE a.token_hash=? AND a.revoked=0 AND d.revoked=0`,
        hash,
      );
    if (!app) reject(401, "unauthorized");
    return {
      device: String(app.device),
      application: String(app.id),
      priority: Number(app.default_priority),
    };
  }
  authorized(attachment: Attachment): boolean {
    return !!this.first(
      "SELECT id FROM devices WHERE id=? AND receive_hash=? AND revoked=0",
      attachment.device,
      attachment.hash,
    );
  }
  active(device: string): void {
    if (!this.first("SELECT id FROM devices WHERE id=? AND revoked=0", device))
      reject(400, "invalid_device");
  }
  createBinding(value: unknown, hash: string, code: string, now: number) {
    const deviceName = name(value, "invalid_binding");
    this.storage.transactionSync(() => {
      this.rows("DELETE FROM binding_codes WHERE expires_at<=?", now);
      if (
        Number(this.first("SELECT count(*) AS n FROM binding_codes")!.n) >= 1000
      )
        reject(507, "storage_capacity_reached");
      this.rows(
        "INSERT INTO binding_codes VALUES(?,?,?,NULL)",
        hash,
        deviceName,
        now + 600_000,
      );
    });
    return {
      binding_code: code,
      device_name: deviceName,
      expires_at_ms: now + 600_000,
    };
  }
  bind(
    codeHash: string,
    value: unknown,
    receiveHash: string,
    pushHash: string,
    receive: string,
    push: string,
    now: number,
  ) {
    const requestedName = name(value ?? "", "invalid_or_expired_binding", true);
    return this.storage.transactionSync(() => {
      const binding = this.first(
        "SELECT device_name FROM binding_codes WHERE code_hash=? AND used_at IS NULL AND expires_at>?",
        codeHash,
        now,
      );
      if (!binding) reject(400, "invalid_or_expired_binding");
      if (Number(this.first("SELECT count(*) AS n FROM devices")!.n) >= 1024)
        reject(507, "storage_capacity_reached");
      const id = token();
      this.rows(
        "UPDATE binding_codes SET used_at=? WHERE code_hash=?",
        now,
        codeHash,
      );
      this.rows(
        "INSERT INTO devices(id,name,receive_hash,push_hash) VALUES(?,?,?,?)",
        id,
        requestedName || binding.device_name,
        receiveHash,
        pushHash,
      );
      return {
        server_id: this.serverID,
        device_id: id,
        receive_token: receive,
        push_key: push,
        protocol: 1,
      };
    });
  }
  revoke(device: string): void {
    this.storage.transactionSync(() => {
      if (!this.first("SELECT id FROM devices WHERE id=?", device))
        reject(400, "invalid_device");
      this.rows("INSERT OR IGNORE INTO session_disconnects VALUES(?)", device);
      this.rows("UPDATE devices SET revoked=1 WHERE id=?", device);
      this.rows(
        "DELETE FROM publish_outbox WHERE message_seq IN (SELECT seq FROM messages WHERE device=?)",
        device,
      );
    });
  }
  devices() {
    return this.rows(
      "SELECT id,name,revoked FROM devices ORDER BY name,id",
    ).map((d) => ({ device_id: d.id, name: d.name, revoked: !!d.revoked }));
  }
  applications() {
    return this.rows(
      `SELECT a.id,a.device,a.name,a.default_priority,(a.revoked OR d.revoked) AS revoked
      FROM applications a JOIN devices d ON d.id=a.device ORDER BY a.name,a.id`,
    ).map((a) => ({
      id: a.id,
      device_id: a.device,
      name: a.name,
      default_priority: a.default_priority,
      revoked: !!a.revoked,
    }));
  }
  createApplication(
    device: string,
    value: unknown,
    priority: number,
    hash: string,
    credential: string,
  ) {
    const appName = name(value, "invalid_application");
    return this.storage.transactionSync(() => {
      this.active(device);
      if (
        Number(this.first("SELECT count(*) AS n FROM applications")!.n) >= 4096
      )
        reject(507, "storage_capacity_reached");
      const id = token();
      this.rows(
        "INSERT INTO applications(id,device,name,token_hash,default_priority) VALUES(?,?,?,?,?)",
        id,
        device,
        appName,
        hash,
        priority,
      );
      return {
        id,
        device_id: device,
        name: appName,
        default_priority: priority,
        revoked: false,
        token: credential,
      };
    });
  }
  revokeApplication(id: string) {
    if (!this.first("SELECT id FROM applications WHERE id=?", id))
      reject(400, "invalid_application");
    this.rows("UPDATE applications SET revoked=1 WHERE id=?", id);
  }
  // Persist admission counters: eviction/hibernation must not refill a burst.
  admit(key: string, now: number, burst: number, rate: number): void {
    const old = this.first(
      "SELECT at,tokens FROM rate_buckets WHERE key=?",
      key,
    );
    const available = old
      ? Math.min(
          burst,
          Number(old.tokens) +
            (Math.max(0, now - Number(old.at)) * rate) / 1000,
        )
      : burst;
    if (available < 1)
      reject(
        429,
        key === "bind" ? "binding_rate_limited" : "push_rate_limited",
      );
    this.rows(
      "INSERT INTO rate_buckets VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET at=excluded.at,tokens=excluded.tokens",
      key,
      now,
      available - 1,
    );
  }
}
