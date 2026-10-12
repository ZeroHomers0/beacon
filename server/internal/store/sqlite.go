// Package store owns SQLite connections, migrations and transactional persistence.
package store

import (
	"database/sql"
	"encoding/json"
	"time"

	"beacon.local/server/internal/auth"
	"beacon.local/server/internal/domain"

	_ "modernc.org/sqlite"
)

type Store struct {
	db       *sql.DB
	serverID string
}

func (a *Store) Close() error { return a.db.Close() }

func (a *Store) ServerID() string { return a.serverID }

func Open(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	ok := false
	defer func() {
		if !ok {
			db.Close()
		}
	}()
	for _, q := range []string{
		"PRAGMA busy_timeout=5000", "PRAGMA journal_mode=WAL", "PRAGMA foreign_keys=ON",
		"CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
		"CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,name TEXT NOT NULL,receive_hash TEXT UNIQUE NOT NULL,push_hash TEXT UNIQUE NOT NULL)",
		"CREATE TABLE IF NOT EXISTS messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,device TEXT NOT NULL REFERENCES devices(id),title TEXT NOT NULL,body TEXT NOT NULL,created INTEGER NOT NULL,acked INTEGER NOT NULL DEFAULT 0)",
		"CREATE INDEX IF NOT EXISTS messages_device_seq ON messages(device,seq)",
	} {
		if _, err = db.Exec(q); err != nil {
			return nil, err
		}
	}
	// Transactional additive migration of the existing unversioned schema.
	if err = migrateMessageSchema(db); err != nil {
		return nil, err
	}
	id, err := auth.NewToken()
	if err != nil {
		return nil, err
	}
	if _, err = db.Exec("INSERT OR IGNORE INTO metadata(key,value) VALUES('server_id',?)", id); err != nil {
		return nil, err
	}
	a := &Store{db: db}
	if err = db.QueryRow("SELECT value FROM metadata WHERE key='server_id'").Scan(&a.serverID); err != nil {
		return nil, err
	}
	ok = true
	return a, nil
}

func (a *Store) CreateDevice(name string) (domain.DeviceCredentials, error) {
	c := domain.DeviceCredentials{ServerID: a.serverID}
	var err error
	if c.DeviceID, err = auth.NewToken(); err != nil {
		return c, err
	}
	if c.ReceiveToken, err = auth.NewToken(); err != nil {
		return c, err
	}
	if c.PushKey, err = auth.NewToken(); err != nil {
		return c, err
	}
	_, err = a.db.Exec("INSERT INTO devices(id,name,receive_hash,push_hash) VALUES(?,?,?,?)", c.DeviceID, name, auth.HashToken(c.ReceiveToken), auth.HashToken(c.PushKey))
	return c, err
}

func (a *Store) AuthenticateToken(token string, push bool) (string, error) {
	if len(token) != domain.TokenLength {
		return "", domain.ErrUnauthorized
	}
	column := "receive_hash"
	if push {
		column = "push_hash"
	}
	var id string
	err := a.db.QueryRow("SELECT id FROM devices WHERE "+column+"=? AND revoked=0", auth.HashToken(token)).Scan(&id)
	return id, err
}

func (a *Store) SaveMessage(device, title, subtitle, body string) (domain.Message, error) {
	return a.SavePriorityMessage(device, title, subtitle, body, domain.DefaultPriority, "", "")
}

func (a *Store) SavePriorityMessage(device, title, subtitle, body string, priority int, applicationID, token string, extensions ...string) (domain.Message, error) {
	bark := ""
	if len(extensions) > 1 {
		return domain.Message{}, domain.ErrInvalidMessage
	}
	if len(extensions) == 1 {
		bark = extensions[0]
	}
	return a.SavePushMessage(device, title, subtitle, body, priority, applicationID, token, bark, domain.PushOptions{})
}

func (a *Store) SavePushMessage(device, title, subtitle, body string, priority int, applicationID, token, bark string, options domain.PushOptions) (domain.Message, error) {
	if !options.Valid() {
		return domain.Message{}, domain.ErrInvalidMessage
	}
	if len(bark) > 100000 {
		return domain.Message{}, domain.ErrInvalidMessage
	}
	if priority < 0 || priority > 10 {
		return domain.Message{}, domain.ErrInvalidMessage
	}
	if len(title) > domain.MaxTitleBytes || len(subtitle) > domain.MaxTitleBytes || len(body) == 0 || len(body) > domain.MaxBodyBytes {
		return domain.Message{}, domain.ErrInvalidMessage
	}
	id, err := auth.NewToken()
	if err != nil {
		return domain.Message{}, err
	}
	now := time.Now().UnixMilli()
	tx, err := a.db.Begin()
	if err != nil {
		return domain.Message{}, err
	}
	defer tx.Rollback()
	// Check revocation in the same transaction; HTTP authentication may have happened earlier.
	var active int
	if err := tx.QueryRow("SELECT count(*) FROM devices WHERE id=? AND revoked=0", device).Scan(&active); err != nil {
		return domain.Message{}, err
	}
	if active != 1 {
		return domain.Message{}, domain.ErrInvalidDevice
	}
	if token != "" && applicationID == "" {
		var valid int
		if err = tx.QueryRow("SELECT count(*) FROM devices WHERE id=? AND push_hash=? AND revoked=0", device, auth.HashToken(token)).Scan(&valid); err != nil {
			return domain.Message{}, err
		}
		if valid != 1 {
			return domain.Message{}, domain.ErrUnauthorized
		}
	}
	applicationName := ""
	if applicationID != "" {
		var count int
		if err := tx.QueryRow(`SELECT count(*) FROM applications WHERE id=? AND device=? AND token_hash=? AND revoked=0`, applicationID, device, auth.HashToken(token)).Scan(&count); err != nil || count != 1 {
			return domain.Message{}, domain.ErrUnauthorized
		}
	}
	if applicationID != "" {
		if err = tx.QueryRow("SELECT name FROM applications WHERE id=?", applicationID).Scan(&applicationName); err != nil {
			return domain.Message{}, err
		}
	}
	credential := auth.HashToken(token)
	actions := ""
	if len(options.Actions) > 0 {
		encoded, _ := json.Marshal(options.Actions)
		actions = string(encoded)
	}
	fingerprintFields := []any{title, subtitle, body, priority, applicationID, bark, options.Group, options.TTL}
	if len(options.Actions) > 0 {
		fingerprintFields = append(fingerprintFields, options.Actions)
	}
	fingerprintBytes, _ := json.Marshal(fingerprintFields)
	fingerprint := auth.HashToken(string(fingerprintBytes))
	if options.Key != "" {
		var previous, response string
		err = tx.QueryRow("SELECT fingerprint,response FROM push_requests WHERE credential=? AND request_key=? AND expires_at>?", credential, options.Key, now).Scan(&previous, &response)
		if err == nil {
			if previous != fingerprint {
				return domain.Message{}, domain.ErrIdempotencyConflict
			}
			var m domain.Message
			err = json.Unmarshal([]byte(response), &m)
			return m, err
		}
		if err != sql.ErrNoRows {
			return domain.Message{}, err
		}
		if _, err = tx.Exec("DELETE FROM push_requests WHERE credential=? AND request_key=?", credential, options.Key); err != nil {
			return domain.Message{}, err
		}
		var requests int
		if err = tx.QueryRow("SELECT count(*) FROM push_requests").Scan(&requests); err != nil {
			return domain.Message{}, err
		}
		if requests >= domain.MaxStoredMessages {
			return domain.Message{}, domain.ErrStorageCapacity
		}
	}
	ttl := options.TTL
	if ttl == 0 {
		ttl = 604800
	}
	expires := now + ttl*1000
	addedBytes := int64(len(title) + len(subtitle) + len(body) + len(bark) + len(actions))
	if err := a.checkDevicePendingQuota(tx, device, addedBytes); err != nil {
		return domain.Message{}, err
	}
	if err := a.checkStoreQuota(tx, addedBytes); err != nil {
		return domain.Message{}, err
	}
	result, err := tx.Exec(`INSERT INTO messages(id,device,title,body,created,subtitle,priority,application_id,bark,message_group,expires_at,application_name,actions)
		SELECT ?,id,?,?,?,?,?,?,?,?,?,?,? FROM devices WHERE id=? AND revoked=0`, id, title, body, now, subtitle, priority, applicationID, bark, options.Group, expires, applicationName, actions, device)
	if err != nil {
		return domain.Message{}, err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return domain.Message{}, err
	}
	if count != 1 {
		return domain.Message{}, domain.ErrInvalidDevice
	}
	sequence, err := result.LastInsertId()
	if err != nil {
		return domain.Message{}, err
	}
	if _, err = tx.Exec("INSERT INTO publish_outbox(message_seq) VALUES(?)", sequence); err != nil {
		return domain.Message{}, err
	}
	m := domain.Message{Actions: options.Actions, ApplicationName: applicationName, ID: id, Sequence: sequence, Title: title, Body: body, CreatedAt: now, Subtitle: subtitle, Priority: priority, ApplicationID: applicationID, Bark: bark, Group: options.Group, ExpiresAt: expires}
	if options.Key != "" {
		response, _ := json.Marshal(m)
		if _, err = tx.Exec("INSERT INTO push_requests VALUES(?,?,?,?,?)", credential, options.Key, fingerprint, string(response), now+604800000); err != nil {
			return domain.Message{}, err
		}
	}
	if err = tx.Commit(); err != nil {
		return domain.Message{}, err
	}
	return m, nil
}

// pendingDeviceUsage returns the unacked count and byte total pending for a device.
func pendingDeviceUsage(tx *sql.Tx, device string) (int64, int64, error) {
	var pending, pendingBytes int64
	err := tx.QueryRow(`SELECT count(*),coalesce(sum(length(CAST(title AS BLOB))+length(CAST(subtitle AS BLOB))+length(CAST(body AS BLOB))+length(CAST(bark AS BLOB))+length(CAST(actions AS BLOB))),0) FROM messages WHERE device=? AND acked=0`, device).Scan(&pending, &pendingBytes)
	return pending, pendingBytes, err
}

// checkDevicePendingQuota enforces the per-device pending count and byte quota.
func (a *Store) checkDevicePendingQuota(tx *sql.Tx, device string, addedBytes int64) error {
	pending, pendingBytes, err := pendingDeviceUsage(tx, device)
	if err != nil {
		return err
	}
	if pending >= domain.MaxPendingPerDevice || pendingBytes+addedBytes > domain.MaxPendingBytesPerDevice {
		return domain.ErrStorageCapacity
	}
	return nil
}

// checkStoreQuota enforces the global logical SQLite quota for a bounded write.
func (a *Store) checkStoreQuota(tx *sql.Tx, addedBytes int64) error {
	var stored, pages, freePages, pageSize int64
	for _, query := range []struct {
		sql    string
		result *int64
	}{
		{"SELECT count(*) FROM messages", &stored},
		{"PRAGMA page_count", &pages},
		{"PRAGMA freelist_count", &freePages},
		{"PRAGMA page_size", &pageSize},
	} {
		if err := tx.QueryRow(query.sql).Scan(query.result); err != nil {
			return err
		}
	}
	// Reserve a bounded write's headroom. Reusable pages do not consume this quota.
	// This is a logical SQLite quota, not a promise about WAL or filesystem usage.
	if stored >= domain.MaxStoredMessages || (pages-freePages)*pageSize+domain.MaxRequestBytes > domain.MaxLiveDatabaseBytes {
		return domain.ErrStorageCapacity
	}
	return nil
}

func (a *Store) Messages(device string, after int64, limit int, pending bool) (domain.MessagePage, error) {
	query := "SELECT id,seq,title,body,created,subtitle,priority,application_id,bark,message_group,expires_at,application_name,actions FROM messages WHERE device=? AND seq>? AND expires_at>?"
	if pending {
		query += " AND acked=0"
	}
	query += " ORDER BY seq LIMIT ?"
	rows, err := a.db.Query(query, device, after, time.Now().UnixMilli(), limit+1)
	if err != nil {
		return domain.MessagePage{}, err
	}
	defer rows.Close()
	items := make([]domain.Message, 0, limit+1)
	for rows.Next() {
		var m domain.Message
		var actions string
		if err := rows.Scan(&m.ID, &m.Sequence, &m.Title, &m.Body, &m.CreatedAt, &m.Subtitle, &m.Priority, &m.ApplicationID, &m.Bark, &m.Group, &m.ExpiresAt, &m.ApplicationName, &actions); err != nil {
			return domain.MessagePage{}, err
		}
		if actions != "" {
			if err := json.Unmarshal([]byte(actions), &m.Actions); err != nil {
				return domain.MessagePage{}, err
			}
		}
		items = append(items, m)
	}
	if err := rows.Err(); err != nil {
		return domain.MessagePage{}, err
	}
	more := len(items) > limit
	if more {
		items = items[:limit]
	}
	cursor := after
	if len(items) > 0 {
		cursor = items[len(items)-1].Sequence
	}
	return domain.MessagePage{ServerID: a.serverID, Messages: items, NextCursor: cursor, HasMore: more}, nil
}

func (a *Store) Ack(device string, ids []string) error {
	tx, err := a.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, id := range ids {
		if len(id) != domain.TokenLength {
			return domain.ErrUnknownMessage
		}
		result, err := tx.Exec("UPDATE messages SET acked=1 WHERE device=? AND id=?", device, id)
		if err != nil {
			return err
		}
		count, err := result.RowsAffected()
		if err != nil {
			return err
		}
		if count != 1 {
			var receipt int
			if err = tx.QueryRow("SELECT count(*) FROM (SELECT id FROM confirmed_receipts WHERE device=? AND id=? AND (expires_at=0 OR expires_at>?) UNION SELECT id FROM expired_receipts WHERE device=? AND id=? AND expires_at>?)", device, id, time.Now().UnixMilli(), device, id, time.Now().UnixMilli()).Scan(&receipt); err != nil {
				return err
			}
			if receipt != 1 {
				return domain.ErrUnknownMessage
			}
		}
		// A client receipt makes any queued realtime publication unnecessary.
		if _, err = tx.Exec(`DELETE FROM publish_outbox WHERE message_seq IN
			(SELECT seq FROM messages WHERE device=? AND id=?)`, device, id); err != nil {
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	return nil
}

func (a *Store) HasPushRequest(token, key string) (bool, error) {
	var count int
	err := a.db.QueryRow("SELECT count(*) FROM push_requests WHERE credential=? AND request_key=? AND expires_at>?", auth.HashToken(token), key, time.Now().UnixMilli()).Scan(&count)
	return count == 1, err
}
