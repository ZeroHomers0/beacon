package store

import (
	"beacon.local/server/internal/auth"
	"beacon.local/server/internal/domain"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"time"
)

func createStateSchema(tx *sql.Tx) error {
	for _, q := range []string{
		`CREATE TABLE IF NOT EXISTS current_states(device TEXT NOT NULL REFERENCES devices(id),application TEXT NOT NULL,topic TEXT NOT NULL,revision INTEGER NOT NULL,digest TEXT NOT NULL,deleted INTEGER NOT NULL,task INTEGER NOT NULL,status TEXT NOT NULL,payload TEXT NOT NULL,expires INTEGER NOT NULL,PRIMARY KEY(device,application,topic))`,
		`CREATE TABLE IF NOT EXISTS state_changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,device TEXT NOT NULL,application TEXT NOT NULL,topic TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)`,
		`CREATE INDEX IF NOT EXISTS state_changes_device ON state_changes(device,seq)`,
		`CREATE TABLE IF NOT EXISTS state_snapshots(token TEXT PRIMARY KEY,device TEXT NOT NULL,payload TEXT NOT NULL,watermark INTEGER NOT NULL,expires INTEGER NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS state_rate(key TEXT PRIMARY KEY,minute INTEGER NOT NULL,n INTEGER NOT NULL)`,
	} {
		if _, err := tx.Exec(q); err != nil {
			return err
		}
	}
	return nil
}

func stateSender(tx *sql.Tx, token string) (device, application, name string, err error) {
	hash := auth.HashToken(token)
	err = tx.QueryRow("SELECT id FROM devices WHERE push_hash=? AND revoked=0", hash).Scan(&device)
	if err == nil {
		return
	}
	err = tx.QueryRow(`SELECT a.device,a.id,a.name FROM applications a JOIN devices d ON d.id=a.device WHERE a.token_hash=? AND a.revoked=0 AND d.revoked=0`, hash).Scan(&device, &application, &name)
	if err != nil {
		err = domain.ErrUnauthorized
	}
	return
}

func (s *Store) SaveState(token string, p domain.StatePayload) (domain.StateRecord, error) {
	now := time.Now().UnixMilli()
	if s.stateClock != nil {
		now = s.stateClock()
	}
	if !p.Valid(now) {
		return domain.StateRecord{}, domain.ErrInvalidMessage
	}
	tx, err := s.db.Begin()
	if err != nil {
		return domain.StateRecord{}, err
	}
	defer tx.Rollback()
	device, app, name, err := stateSender(tx, token)
	if err != nil {
		return domain.StateRecord{}, err
	}
	var old domain.StateRecord
	var previous *domain.StateVersion
	var raw, digest string
	var revision int64
	var deleted, task bool
	var status string
	err = tx.QueryRow("SELECT revision,digest,deleted,task,status,payload FROM current_states WHERE device=? AND application=? AND topic=?", device, app, p.TopicKey).Scan(&revision, &digest, &deleted, &task, &status, &raw)
	if err == nil {
		var d [32]byte
		b, _ := hex.DecodeString(digest)
		copy(d[:], b)
		previous = &domain.StateVersion{Revision: revision, Digest: d, Deleted: deleted, Task: task, Status: status}
		if err = json.Unmarshal([]byte(raw), &old); err != nil {
			return old, err
		}
	} else if !errors.Is(err, sql.ErrNoRows) {
		return old, err
	}
	var digestValue any = p
	if len(p.DigestInput) > 0 {
		digestValue = p.DigestInput
	}
	d, err := domain.StateDigest(digestValue)
	if err != nil {
		return old, err
	}
	next := domain.StateVersion{Revision: p.Revision, Digest: d, Deleted: p.Operation == "delete", Task: p.StateType == "task", Status: p.Status}
	if previous != nil && next.Deleted {
		next.Task = previous.Task
		next.Status = previous.Status
	}
	if previous != nil && previous.Revision == next.Revision && previous.Digest == next.Digest && previous.Deleted && !next.Deleted {
		old.Unchanged = true
		return old, nil
	}
	same, err := domain.CheckStateVersion(previous, next)
	if err != nil {
		return old, err
	}
	if same {
		old.Unchanged = true
		return old, nil
	}
	for _, limit := range []struct {
		key string
		max int
	}{{"device:" + device, 300}, {"application:" + device + ":" + app, 120}} {
		_, err = tx.Exec(`INSERT INTO state_rate VALUES(?,?,1) ON CONFLICT(key) DO UPDATE SET n=CASE WHEN minute=excluded.minute THEN n+1 ELSE 1 END,minute=excluded.minute`, limit.key, now/60000)
		if err != nil {
			return old, err
		}
		var count int
		if err = tx.QueryRow("SELECT n FROM state_rate WHERE key=?", limit.key).Scan(&count); err != nil {
			return old, err
		}
		if count > limit.max {
			return old, domain.ErrRateLimited
		}
	}
	var count, used int64
	if err = tx.QueryRow("SELECT count(*),coalesce(sum(length(CAST(payload AS BLOB))*(deleted=0)),0) FROM current_states WHERE device=?", device).Scan(&count, &used); err != nil {
		return old, err
	}
	record := domain.StateRecord{StatePayload: p, ApplicationID: app, ApplicationName: name, AcceptedAt: now, Deleted: next.Deleted}
	if !record.Deleted {
		ttl := p.TTL
		if ttl == 0 {
			ttl = 604800
		}
		record.ExpiresAt = now + ttl*1000
		if p.FreshFor > 0 {
			record.StaleAt = p.ObservedAt + p.FreshFor*1000
		}
	}
	encoded, _ := json.Marshal(record)
	if err = stateStorageCapacity(tx, int64(len(encoded)*3)+domain.MaxRequestBytes); err != nil {
		return old, err
	}
	if (previous == nil && count >= 500) || used-func() int64 {
		if previous != nil && !previous.Deleted {
			return int64(len(raw))
		}
		return 0
	}()+int64(len(encoded)) > 8*1024*1024 {
		return old, domain.ErrStorageCapacity
	}
	_, err = tx.Exec(`INSERT INTO current_states VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(device,application,topic) DO UPDATE SET revision=excluded.revision,digest=excluded.digest,deleted=excluded.deleted,task=excluded.task,status=excluded.status,payload=excluded.payload,expires=excluded.expires`, device, app, p.TopicKey, p.Revision, hex.EncodeToString(d[:]), next.Deleted, next.Task, next.Status, string(encoded), record.ExpiresAt)
	if err != nil {
		return old, err
	}
	if err = appendStateChange(tx, device, app, p.TopicKey, string(encoded), now); err != nil {
		return old, err
	}
	return record, tx.Commit()
}

func appendStateChange(tx *sql.Tx, device, app, topic, payload string, now int64) error {
	_, err := tx.Exec("INSERT INTO state_changes(device,application,topic,payload,created) VALUES(?,?,?,?,?)", device, app, topic, payload, now)
	if err != nil {
		return err
	}
	_, err = tx.Exec("DELETE FROM state_changes WHERE device=? AND (created<? OR seq < coalesce((SELECT seq FROM state_changes WHERE device=? ORDER BY seq DESC LIMIT 1 OFFSET 9999),0))", device, now-86400000, device)
	return err
}

func (s *Store) StateMeta(token, topic string) (map[string]any, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	device, app, _, err := stateSender(tx, token)
	if err != nil {
		return nil, err
	}
	var revision int64
	var deleted bool
	var status string
	err = tx.QueryRow("SELECT revision,deleted,status FROM current_states WHERE device=? AND application=? AND topic=?", device, app, topic).Scan(&revision, &deleted, &status)
	if errors.Is(err, sql.ErrNoRows) {
		return map[string]any{"revision": 0, "deleted": false, "status": ""}, nil
	}
	return map[string]any{"revision": revision, "deleted": deleted, "status": status}, err
}
