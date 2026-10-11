// Package store owns SQLite connections, migrations and transactional persistence.
package store

import (
	"database/sql"
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
	if applicationID != "" {
		var count int
		if err := tx.QueryRow(`SELECT count(*) FROM applications WHERE id=? AND device=? AND token_hash=? AND revoked=0`, applicationID, device, auth.HashToken(token)).Scan(&count); err != nil || count != 1 {
			return domain.Message{}, domain.ErrUnauthorized
		}
	}
	addedBytes := int64(len(title) + len(subtitle) + len(body) + len(bark))
	if err := a.checkDevicePendingQuota(tx, device, addedBytes); err != nil {
		return domain.Message{}, err
	}
	if err := a.checkStoreQuota(tx, addedBytes); err != nil {
		return domain.Message{}, err
	}
	result, err := tx.Exec(`INSERT INTO messages(id,device,title,body,created,subtitle,priority,application_id,bark)
		SELECT ?,id,?,?,?,?,?,?,? FROM devices WHERE id=? AND revoked=0`, id, title, body, now, subtitle, priority, applicationID, bark, device)
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
	if err = tx.Commit(); err != nil {
		return domain.Message{}, err
	}
	return domain.Message{ID: id, Sequence: sequence, Title: title, Body: body, CreatedAt: now, Subtitle: subtitle, Priority: priority, ApplicationID: applicationID, Bark: bark}, nil
}

// pendingDeviceUsage returns the unacked count and byte total pending for a device.
func pendingDeviceUsage(tx *sql.Tx, device string) (int64, int64, error) {
	var pending, pendingBytes int64
	err := tx.QueryRow(`SELECT count(*),coalesce(sum(length(CAST(title AS BLOB))+length(CAST(subtitle AS BLOB))+length(CAST(body AS BLOB))+length(CAST(bark AS BLOB))),0) FROM messages WHERE device=? AND acked=0`, device).Scan(&pending, &pendingBytes)
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
	query := "SELECT id,seq,title,body,created,subtitle,priority,application_id,bark FROM messages WHERE device=? AND seq>? AND created>?"
	if pending {
		query += " AND acked=0"
	}
	query += " ORDER BY seq LIMIT ?"
	rows, err := a.db.Query(query, device, after, time.Now().Add(-domain.PendingLifetime).UnixMilli(), limit+1)
	if err != nil {
		return domain.MessagePage{}, err
	}
	defer rows.Close()
	items := make([]domain.Message, 0, limit+1)
	for rows.Next() {
		var m domain.Message
		if err := rows.Scan(&m.ID, &m.Sequence, &m.Title, &m.Body, &m.CreatedAt, &m.Subtitle, &m.Priority, &m.ApplicationID, &m.Bark); err != nil {
			return domain.MessagePage{}, err
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
