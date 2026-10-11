import { token } from "../domain";

// Workers keeps its own schema version, independent of the Go server database.
export function migrate(storage: DurableObjectStorage): void {
  storage.transactionSync(() => {
    const sql = storage.sql;
    sql.exec(
      "CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
    );
    const version = [
      ...sql.exec("SELECT value FROM metadata WHERE key='schema_version'"),
    ][0];
    if (version && version.value !== "1")
      throw new Error("unsupported workers schema");
    if (version) return;
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
  });
}
