import { topicSchema } from "../topics";
import { stateSchema } from "../states";
import { token } from "../domain";

// Workers keeps its own schema version, independent of the Go server database.
export function migrate(storage: DurableObjectStorage): void {
  storage.transactionSync(() => {
    const sql = storage.sql;
    stateSchema(sql);
    topicSchema(sql);
    sql.exec(
      "CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
    );
    const version = [
      ...sql.exec("SELECT value FROM metadata WHERE key='schema_version'"),
    ][0];
    if (version?.value === "4") return;
    if (version?.value === "3") { upgradeBlocks(sql); return; }
    if (version?.value === "2") {
      upgradeActions(sql);
      upgradeBlocks(sql);
      return;
    }
    if (version && version.value !== "1")
      throw new Error("unsupported workers schema");
    if (version) {
      upgrade(sql);
      upgradeActions(sql);
      upgradeBlocks(sql);
      return;
    }
    sql.exec(`CREATE TABLE devices(id TEXT PRIMARY KEY,name TEXT NOT NULL,receive_hash TEXT UNIQUE NOT NULL,
      push_hash TEXT UNIQUE NOT NULL,revoked INTEGER NOT NULL DEFAULT 0,expired_messages INTEGER NOT NULL DEFAULT 0,
      pending_count INTEGER NOT NULL DEFAULT 0,pending_bytes INTEGER NOT NULL DEFAULT 0)`);
    sql.exec(
      `CREATE TABLE binding_codes(code_hash TEXT PRIMARY KEY,device_name TEXT NOT NULL,expires_at INTEGER NOT NULL,used_at INTEGER)`,
    );
    sql.exec(`CREATE TABLE applications(id TEXT PRIMARY KEY,device TEXT NOT NULL REFERENCES devices(id),name TEXT NOT NULL,
      token_hash TEXT UNIQUE NOT NULL,default_priority INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0)`);
    sql.exec(`CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,
      device TEXT NOT NULL REFERENCES devices(id),title TEXT NOT NULL,subtitle TEXT NOT NULL,body TEXT NOT NULL,
      created INTEGER NOT NULL,acked INTEGER NOT NULL DEFAULT 0,priority INTEGER NOT NULL,application_id TEXT NOT NULL DEFAULT '',bark TEXT NOT NULL DEFAULT '')`);
    sql.exec("CREATE INDEX messages_device_seq ON messages(device,seq)");
    sql.exec(
      "CREATE INDEX messages_pending_created ON messages(acked,created,seq)",
    );
    // Quota counters are maintained by SQL triggers in the same transaction,
    // avoiding a full body scan on every push and every device admission check.
    sql.exec(
      "CREATE TABLE usage(singleton INTEGER PRIMARY KEY CHECK(singleton=1),messages INTEGER NOT NULL DEFAULT 0)",
    );
    sql.exec("INSERT INTO usage(singleton) VALUES(1)");
    const size =
      "length(CAST(NEW.title AS BLOB))+length(CAST(NEW.subtitle AS BLOB))+length(CAST(NEW.body AS BLOB))+length(CAST(NEW.bark AS BLOB))";
    const oldSize = size.replaceAll("NEW.", "OLD.");
    sql.exec(`CREATE TRIGGER messages_insert AFTER INSERT ON messages BEGIN
      UPDATE usage SET messages=messages+1 WHERE singleton=1;
      UPDATE devices SET pending_count=pending_count+(NEW.acked=0),pending_bytes=pending_bytes+(NEW.acked=0)*(${size}) WHERE id=NEW.device;
      END`);
    sql.exec(`CREATE TRIGGER messages_delete AFTER DELETE ON messages BEGIN
      UPDATE usage SET messages=messages-1 WHERE singleton=1;
      UPDATE devices SET pending_count=pending_count-(OLD.acked=0),pending_bytes=pending_bytes-(OLD.acked=0)*(${oldSize}) WHERE id=OLD.device;
      END`);
    sql.exec(`CREATE TRIGGER messages_ack AFTER UPDATE OF acked ON messages WHEN OLD.acked<>NEW.acked BEGIN
      UPDATE devices SET pending_count=pending_count+(NEW.acked=0)-(OLD.acked=0),
        pending_bytes=pending_bytes+((NEW.acked=0)-(OLD.acked=0))*(${size}) WHERE id=NEW.device;
      END`);
    sql.exec(`CREATE TABLE publish_outbox(message_seq INTEGER PRIMARY KEY REFERENCES messages(seq) ON DELETE CASCADE,
      attempts INTEGER NOT NULL DEFAULT 0,next_attempt INTEGER NOT NULL DEFAULT 0)`);
    sql.exec(
      "CREATE INDEX outbox_due ON publish_outbox(next_attempt,message_seq)",
    );
    sql.exec(
      `CREATE TABLE session_disconnects(device TEXT PRIMARY KEY REFERENCES devices(id))`,
    );
    for (const table of ["confirmed_receipts", "expired_receipts"]) {
      sql.exec(
        `CREATE TABLE ${table}(device TEXT NOT NULL REFERENCES devices(id),id TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(device,id))`,
      );
      sql.exec(`CREATE INDEX ${table}_expires ON ${table}(expires_at)`);
    }
    sql.exec(
      "CREATE TABLE rate_buckets(key TEXT PRIMARY KEY,at INTEGER NOT NULL,tokens REAL NOT NULL)",
    );
    sql.exec(
      "INSERT INTO metadata VALUES('server_id',?),('schema_version','1')",
      token(),
    );
    upgrade(sql);
    upgradeActions(sql);
    upgradeBlocks(sql);
  });
}

function upgrade(sql: SqlStorage): void {
  sql.exec(
    "ALTER TABLE messages ADD COLUMN application_name TEXT NOT NULL DEFAULT ''",
  );
  sql.exec(
    "ALTER TABLE messages ADD COLUMN message_group TEXT NOT NULL DEFAULT ''",
  );
  sql.exec(
    "ALTER TABLE messages ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0",
  );
  sql.exec("UPDATE messages SET expires_at=created+604800000");
  sql.exec(
    "UPDATE messages SET application_name=coalesce((SELECT name FROM applications WHERE id=messages.application_id),'')",
  );
  sql.exec("CREATE INDEX messages_expiry ON messages(acked,expires_at,seq)");
  sql.exec(
    "CREATE TABLE push_requests(credential TEXT NOT NULL,request_key TEXT NOT NULL,fingerprint TEXT NOT NULL,response TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(credential,request_key))",
  );
  sql.exec("CREATE INDEX push_requests_expiry ON push_requests(expires_at)");
  sql.exec("UPDATE metadata SET value='2' WHERE key='schema_version'");
}

function upgradeActions(sql: SqlStorage): void {
  sql.exec("ALTER TABLE messages ADD COLUMN actions TEXT NOT NULL DEFAULT ''");
  for (const trigger of ["messages_insert", "messages_delete", "messages_ack"])
    sql.exec(`DROP TRIGGER IF EXISTS ${trigger}`);
  const size = "length(CAST(NEW.title AS BLOB))+length(CAST(NEW.subtitle AS BLOB))+length(CAST(NEW.body AS BLOB))+length(CAST(NEW.bark AS BLOB))+length(CAST(NEW.actions AS BLOB))";
  const oldSize = size.replaceAll("NEW.", "OLD.");
  sql.exec(`CREATE TRIGGER messages_insert AFTER INSERT ON messages BEGIN
    UPDATE usage SET messages=messages+1 WHERE singleton=1;
    UPDATE devices SET pending_count=pending_count+(NEW.acked=0),pending_bytes=pending_bytes+(NEW.acked=0)*(${size}) WHERE id=NEW.device;
    END`);
  sql.exec(`CREATE TRIGGER messages_delete AFTER DELETE ON messages BEGIN
    UPDATE usage SET messages=messages-1 WHERE singleton=1;
    UPDATE devices SET pending_count=pending_count-(OLD.acked=0),pending_bytes=pending_bytes-(OLD.acked=0)*(${oldSize}) WHERE id=OLD.device;
    END`);
  sql.exec(`CREATE TRIGGER messages_ack AFTER UPDATE OF acked ON messages WHEN OLD.acked<>NEW.acked BEGIN
    UPDATE devices SET pending_count=pending_count+(NEW.acked=0)-(OLD.acked=0),
      pending_bytes=pending_bytes+((NEW.acked=0)-(OLD.acked=0))*(${size}) WHERE id=NEW.device;
    END`);
  sql.exec("UPDATE metadata SET value='3' WHERE key='schema_version'");
}

function upgradeBlocks(sql: SqlStorage): void {
 const columns = new Set([...sql.exec("PRAGMA table_info(messages)")].map(row=>String(row.name)));
 if (!columns.has("blocks")) sql.exec("ALTER TABLE messages ADD COLUMN blocks TEXT NOT NULL DEFAULT ''");
 if (!columns.has("schema_version")) sql.exec("ALTER TABLE messages ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 0");
 for (const name of ["messages_insert","messages_delete","messages_ack"]) {
  const trigger = [...sql.exec("SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?",name)][0];
  if (!trigger) throw new Error("missing message accounting trigger");
  const updated = String(trigger.sql).replaceAll("length(CAST(NEW.actions AS BLOB))", "length(CAST(NEW.actions AS BLOB))+length(CAST(NEW.blocks AS BLOB))").replaceAll("length(CAST(OLD.actions AS BLOB))", "length(CAST(OLD.actions AS BLOB))+length(CAST(OLD.blocks AS BLOB))");
  sql.exec(`DROP TRIGGER ${name}`); sql.exec(updated);
 }
 sql.exec("UPDATE metadata SET value='4' WHERE key='schema_version'");
}
