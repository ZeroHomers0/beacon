/** Topic schema and its one-time legacy backfill; invoked inside migrate's transaction. */
export function topicSchema(sql: SqlStorage) {
  const exists =
    [
      ...sql.exec(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='topic_catalog'",
      ),
    ].length > 0;
  for (const q of [
    "CREATE TABLE IF NOT EXISTS topic_catalog(device TEXT NOT NULL REFERENCES devices(id),application TEXT NOT NULL,topic TEXT NOT NULL,name TEXT NOT NULL,version INTEGER NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,deleted INTEGER NOT NULL,request_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(device,application,topic))",
    "CREATE UNIQUE INDEX IF NOT EXISTS topic_request ON topic_catalog(device,request_id) WHERE request_id<>''",
    "CREATE TABLE IF NOT EXISTS topic_changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,device TEXT NOT NULL,application TEXT NOT NULL,topic TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS topic_changes_device ON topic_changes(device,seq)",
    "CREATE TABLE IF NOT EXISTS topic_snapshots(token TEXT PRIMARY KEY,device TEXT NOT NULL,payload TEXT NOT NULL,watermark INTEGER NOT NULL,expires INTEGER NOT NULL)",
  ])
    sql.exec(q);
  if (
    !exists &&
    [
      ...sql.exec(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='devices'",
      ),
    ].length
  )
    sql.exec(
      `INSERT OR IGNORE INTO topic_catalog SELECT device,application,topic,'',1,0,0,0,'',json_object('device_id',device,'application_id',application,'topic_key',topic,'name','','application_name','','config_version',1,'created_at_ms',0,'updated_at_ms',0,'deleted',json('false')) FROM current_states`,
    );
}
