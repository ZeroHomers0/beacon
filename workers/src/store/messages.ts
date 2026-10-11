import {
  BATCH,
  GRACE,
  Message,
  PENDING,
  RECEIPT,
  Row,
  Sender,
  bytes,
  message,
  reject,
  retry,
  token,
} from "../domain";
import { IdentityStore } from "./identity";
import type { Repository } from "../ports";

export class MessageStore extends IdentityStore implements Repository {
  expiredCount(device: string): number {
    return Number(
      this.first(
        "SELECT expired_messages FROM devices WHERE id=? AND revoked=0",
        device,
      )!.expired_messages,
    );
  }
  save(
    sender: Sender,
    title: string,
    subtitle: string,
    body: string,
    priority: number,
    bark: string,
    now: number,
  ): Message {
    if (
      bytes(title) > 1024 ||
      bytes(subtitle) > 1024 ||
      !body ||
      bytes(body) > 65536 ||
      bytes(bark) > 100000
    )
      reject(400, "invalid_message");
    return this.storage.transactionSync(() => {
      this.active(sender.device);
      this.admit("push", now, 1000, 100);
      this.admit(`device:${sender.device}`, now, 200, 10);
      const usage = this.first(
        "SELECT pending_count,pending_bytes FROM devices WHERE id=?",
        sender.device,
      )!;
      const live = this.first("SELECT messages FROM usage WHERE singleton=1")!;
      if (
        Number(usage.pending_count) >= 10000 ||
        Number(usage.pending_bytes) + bytes(title + subtitle + body + bark) >
          64 * 1024 * 1024 ||
        Number(live.messages) >= 100000 ||
        this.storage.sql.databaseSize + 512 * 1024 > 256 * 1024 * 1024
      )
        reject(507, "storage_capacity_reached");
      const id = token();
      const row = this.first(
        `INSERT INTO messages(id,device,title,subtitle,body,priority,application_id,bark,created)
        VALUES(?,?,?,?,?,?,?,?,?) RETURNING *`,
        id,
        sender.device,
        title,
        subtitle,
        body,
        priority,
        sender.application,
        bark,
        now,
      )!;
      this.rows(
        "INSERT INTO publish_outbox(message_seq,next_attempt) VALUES(?,?)",
        row.seq,
        now,
      );
      return message(row);
    });
  }
  page(
    device: string,
    after: number,
    limit: number,
    pending: boolean,
    now: number,
  ) {
    const rows = this.rows(
      `SELECT * FROM messages WHERE device=? AND seq>? AND created>?${pending ? " AND acked=0" : ""} ORDER BY seq LIMIT ?`,
      device,
      after,
      now - PENDING,
      limit + 1,
    );
    const items = rows.slice(0, limit).map(message);
    return {
      server_id: this.serverID,
      messages: items,
      next_cursor: items.at(-1)?.sequence ?? after,
      has_more: rows.length > limit,
    };
  }
  available(device: string, id: string, now: number): boolean {
    return !!this.first(
      `SELECT id FROM messages WHERE device=? AND id=? UNION
      SELECT id FROM confirmed_receipts WHERE device=? AND id=? AND expires_at>? UNION
      SELECT id FROM expired_receipts WHERE device=? AND id=? AND expires_at>?`,
      device,
      id,
      device,
      id,
      now,
      device,
      id,
      now,
    );
  }
  statuses(device: string, ids: string[], now: number) {
    return Object.fromEntries(
      ids.map((id) => [
        id,
        this.available(device, id, now) ? "available" : "missing",
      ]),
    );
  }
  ack(device: string, ids: string[], now: number): void {
    this.storage.transactionSync(() => {
      // Validate the whole batch before mutating; never confirm unknown/other-device IDs.
      for (const id of ids)
        if (!this.available(device, id, now)) reject(400, "unknown_message");
      for (const id of ids) {
        this.rows(
          "UPDATE messages SET acked=1 WHERE device=? AND id=?",
          device,
          id,
        );
        this.rows(
          "DELETE FROM publish_outbox WHERE message_seq IN (SELECT seq FROM messages WHERE device=? AND id=?)",
          device,
          id,
        );
      }
    });
  }
  due(now: number, device?: string): Row[] {
    return this.rows(
      `SELECT m.*,o.attempts FROM publish_outbox o JOIN messages m ON m.seq=o.message_seq
      JOIN devices d ON d.id=m.device WHERE m.acked=0 AND d.revoked=0 AND m.created>?
      ${device ? "AND m.device=?" : "AND o.next_attempt<=?"} ORDER BY o.next_attempt,m.seq LIMIT ?`,
      now - PENDING,
      device ?? now,
      BATCH,
    );
  }
  complete(seq: number, delivered: boolean, attempts: number, now: number) {
    if (delivered)
      this.rows("DELETE FROM publish_outbox WHERE message_seq=?", seq);
    else
      this.rows(
        "UPDATE publish_outbox SET attempts=?,next_attempt=? WHERE message_seq=?",
        Math.min(30, attempts + 1),
        now + retry(attempts + 1),
        seq,
      );
  }
  stats() {
    const counts = this
      .first(`SELECT count(*) AS messages,coalesce(sum(acked=0),0) AS pending,coalesce(sum(acked=1),0) AS confirmed,
      coalesce(min(CASE WHEN acked=0 THEN created END),0) AS oldest_pending_created_ms,
      coalesce(min(CASE WHEN acked=1 THEN created END),0) AS oldest_confirmed_created_ms FROM messages`)!;
    return {
      ...counts,
      devices: this.first("SELECT count(*) AS n FROM devices")!.n,
      cleanup_confirmation_receipts: this.first(
        "SELECT count(*) AS n FROM confirmed_receipts",
      )!.n,
      database_allocated_bytes: this.storage.sql.databaseSize,
      // Durable Object SQL does not expose page/freelist PRAGMAs. Omit unknown
      // reusable bytes instead of reporting a fabricated SQLite metric.
      queued_realtime_messages: this.first(
        "SELECT count(*) AS n FROM publish_outbox",
      )!.n,
      queued_session_disconnects: this.first(
        "SELECT count(*) AS n FROM session_disconnects",
      )!.n,
    };
  }
  // Bounded maintenance. Receipt expiry mirrors Go's 7d + 30d + grace contract.
  maintain(now: number, retentionDays: number): boolean {
    return this.storage.transactionSync(() => {
      const expired = this.rows(
        "SELECT seq,id,device,created FROM messages WHERE acked=0 AND created<=? ORDER BY created,seq LIMIT ?",
        now - PENDING,
        BATCH,
      );
      for (const row of expired) {
        this.rows(
          "INSERT OR IGNORE INTO expired_receipts VALUES(?,?,?)",
          row.device,
          row.id,
          Number(row.created) + PENDING + RECEIPT + GRACE,
        );
        this.rows(
          "UPDATE devices SET expired_messages=expired_messages+1 WHERE id=?",
          row.device,
        );
        this.rows("DELETE FROM messages WHERE seq=?", row.seq);
      }
      let full = expired.length === BATCH;
      if (retentionDays > 0) {
        const confirmed = this.rows(
          "SELECT seq,id,device,created FROM messages WHERE acked=1 AND created<? ORDER BY created,seq LIMIT ?",
          now - retentionDays * 86400000,
          BATCH,
        );
        for (const row of confirmed) {
          this.rows(
            "INSERT OR IGNORE INTO confirmed_receipts VALUES(?,?,?)",
            row.device,
            row.id,
            Math.max(
              now + RECEIPT,
              Number(row.created) + PENDING + RECEIPT + GRACE,
            ),
          );
          this.rows("DELETE FROM messages WHERE seq=?", row.seq);
        }
        full ||= confirmed.length === BATCH;
      }
      for (const table of ["confirmed_receipts", "expired_receipts"]) {
        const deleted = this.rows(
          `DELETE FROM ${table} WHERE rowid IN (SELECT rowid FROM ${table} WHERE expires_at<=? ORDER BY expires_at LIMIT ?) RETURNING id`,
          now,
          BATCH,
        );
        full ||= deleted.length === BATCH;
      }
      this.rows("DELETE FROM binding_codes WHERE expires_at<=?", now);
      this.rows("DELETE FROM rate_buckets WHERE at<?", now - 120000);
      this.rows(
        "INSERT INTO metadata VALUES('maintenance_due',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        String(now + (full ? 1000 : 3600000)),
      );
      return full;
    });
  }
  nextWake(now: number): number {
    const delivery = this.first(
      `SELECT min(o.next_attempt) AS n FROM publish_outbox o
      JOIN messages m ON m.seq=o.message_seq JOIN devices d ON d.id=m.device
      WHERE m.acked=0 AND d.revoked=0 AND m.created>?`,
      now - PENDING,
    )!.n;
    const maintenance = Number(
      this.first("SELECT value FROM metadata WHERE key='maintenance_due'")
        ?.value ?? now + 3600000,
    );
    const disconnect = this.first(
      "SELECT device FROM session_disconnects LIMIT 1",
    )
      ? now + 1000
      : Infinity;
    return Math.max(
      now + 1000,
      Math.min(
        maintenance,
        delivery === null ? Infinity : Number(delivery),
        disconnect,
      ),
    );
  }
}
