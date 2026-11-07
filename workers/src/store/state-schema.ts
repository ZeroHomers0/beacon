export function stateSchema(sql: SqlStorage): void {
  for (const q of [
    "CREATE TABLE IF NOT EXISTS current_states(device TEXT NOT NULL REFERENCES devices(id),application TEXT NOT NULL,topic TEXT NOT NULL,revision INTEGER NOT NULL,digest TEXT NOT NULL,deleted INTEGER NOT NULL,task INTEGER NOT NULL,status TEXT NOT NULL,payload TEXT NOT NULL,expires INTEGER NOT NULL,PRIMARY KEY(device,application,topic))",
    "CREATE TABLE IF NOT EXISTS state_changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,device TEXT NOT NULL,application TEXT NOT NULL,topic TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS state_changes_device ON state_changes(device,seq)",
    "CREATE TABLE IF NOT EXISTS state_snapshots(token TEXT PRIMARY KEY,device TEXT NOT NULL,payload TEXT NOT NULL,watermark INTEGER NOT NULL,expires INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS state_rate(key TEXT PRIMARY KEY,minute INTEGER NOT NULL,n INTEGER NOT NULL)",
  ])
    sql.exec(q);
}
