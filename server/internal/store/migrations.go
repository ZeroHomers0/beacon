package store

import (
	"beacon.local/server/internal/domain"
	"database/sql"
	"fmt"
	"strconv"
	"time"
)

// messageSchemaVersion is the PRAGMA user_version this binary produces and the highest
// one it accepts. Every migration step below adds exactly one to it.
const messageSchemaVersion = 11

func migrateMessageSchema(db *sql.DB) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var version int
	if err = tx.QueryRow("PRAGMA user_version").Scan(&version); err != nil {
		return err
	}
	if version > messageSchemaVersion {
		return fmt.Errorf("unsupported database schema")
	}
	rows, err := tx.Query("PRAGMA table_info(messages)")
	if err != nil {
		return err
	}
	found := false
	for rows.Next() {
		var cid, notNull, primary int
		var name, kind string
		var defaultValue any
		if err = rows.Scan(&cid, &name, &kind, &notNull, &defaultValue, &primary); err != nil {
			rows.Close()
			return err
		}
		if name == "subtitle" {
			found = true
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	if !found {
		if _, err = tx.Exec("ALTER TABLE messages ADD COLUMN subtitle TEXT NOT NULL DEFAULT ''"); err != nil {
			return err
		}
	}
	if version < 2 {
		var revokedColumns int
		if err = tx.QueryRow("SELECT count(*) FROM pragma_table_info('devices') WHERE name='revoked'").Scan(&revokedColumns); err != nil {
			return err
		}
		if revokedColumns == 0 {
			if _, err = tx.Exec("ALTER TABLE devices ADD COLUMN revoked INTEGER NOT NULL DEFAULT 0"); err != nil {
				return err
			}
		}
	}
	if _, err = tx.Exec(`CREATE TABLE IF NOT EXISTS binding_codes(
		code_hash TEXT PRIMARY KEY, device_name TEXT NOT NULL,
		expires_at INTEGER NOT NULL, used_at INTEGER)`); err != nil {
		return err
	}
	if _, err = tx.Exec(`CREATE TABLE IF NOT EXISTS confirmed_receipts(
		device TEXT NOT NULL REFERENCES devices(id), id TEXT NOT NULL,
		PRIMARY KEY(device,id))`); err != nil {
		return err
	}
	if _, err = tx.Exec("CREATE INDEX IF NOT EXISTS messages_cleanup ON messages(acked,created)"); err != nil {
		return err
	}
	if _, err = tx.Exec(`CREATE TABLE IF NOT EXISTS publish_outbox(
		message_seq INTEGER PRIMARY KEY REFERENCES messages(seq) ON DELETE CASCADE,
		attempts INTEGER NOT NULL DEFAULT 0,
		next_attempt INTEGER NOT NULL DEFAULT 0,
		lease_until INTEGER NOT NULL DEFAULT 0,
		lease_token TEXT NOT NULL DEFAULT '')`); err != nil {
		return err
	}
	if _, err = tx.Exec("CREATE INDEX IF NOT EXISTS publish_outbox_ready ON publish_outbox(next_attempt,lease_until,message_seq)"); err != nil {
		return err
	}
	if version < 4 {
		// Recover pending history once. Reopening must not recreate completed publications.
		if _, err = tx.Exec(`INSERT OR IGNORE INTO publish_outbox(message_seq)
			SELECT m.seq FROM messages m JOIN devices d ON d.id=m.device
			WHERE m.acked=0 AND d.revoked=0`); err != nil {
			return err
		}
	}
	// Preserve pending credential-generation barriers from schema7 deployments.
	var legacySessions int
	if err = tx.QueryRow("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='broker_disconnects'").Scan(&legacySessions); err != nil {
		return err
	}
	if legacySessions > 0 {
		if _, err = tx.Exec("ALTER TABLE broker_disconnects RENAME TO session_disconnects"); err != nil {
			return err
		}
	}
	if _, err = tx.Exec(`CREATE TABLE IF NOT EXISTS session_disconnects(
		client_id TEXT PRIMARY KEY, device TEXT NOT NULL REFERENCES devices(id),
		until_ms INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
		next_attempt INTEGER NOT NULL DEFAULT 0, lease_until INTEGER NOT NULL DEFAULT 0,
		lease_token TEXT NOT NULL DEFAULT '')`); err != nil {
		return err
	}
	if _, err = tx.Exec("CREATE INDEX IF NOT EXISTS session_disconnects_ready ON session_disconnects(next_attempt,lease_until)"); err != nil {
		return err
	}
	if _, err = tx.Exec("CREATE INDEX IF NOT EXISTS session_disconnects_device ON session_disconnects(device)"); err != nil {
		return err
	}
	if version < 6 {
		for _, column := range []struct{ name, definition string }{
			{"priority", "INTEGER NOT NULL DEFAULT 5 CHECK(priority BETWEEN 0 AND 10)"},
			{"application_id", "TEXT NOT NULL DEFAULT ''"},
		} {
			var count int
			if err = tx.QueryRow("SELECT count(*) FROM pragma_table_info('messages') WHERE name=?", column.name).Scan(&count); err != nil {
				return err
			}
			if count == 0 {
				if _, err = tx.Exec("ALTER TABLE messages ADD COLUMN " + column.name + " " + column.definition); err != nil {
					return err
				}
			}
		}
		if _, err = tx.Exec(`CREATE TABLE IF NOT EXISTS applications(id TEXT PRIMARY KEY,device TEXT NOT NULL REFERENCES devices(id),name TEXT NOT NULL,token_hash TEXT UNIQUE NOT NULL,default_priority INTEGER NOT NULL CHECK(default_priority BETWEEN 0 AND 10),revoked INTEGER NOT NULL DEFAULT 0)`); err != nil {
			return err
		}
	}
	if version < 7 {
		for _, column := range []struct{ table, name, definition string }{
			{"devices", "expired_messages", "INTEGER NOT NULL DEFAULT 0"},
			{"confirmed_receipts", "expires_at", "INTEGER NOT NULL DEFAULT 0"},
		} {
			var count int
			if err = tx.QueryRow("SELECT count(*) FROM pragma_table_info('"+column.table+"') WHERE name=?", column.name).Scan(&count); err != nil {
				return err
			}
			if count == 0 {
				if _, err = tx.Exec("ALTER TABLE " + column.table + " ADD COLUMN " + column.name + " " + column.definition); err != nil {
					return err
				}
			}
		}
	}
	if _, err = tx.Exec("CREATE TABLE IF NOT EXISTS expired_receipts(device TEXT NOT NULL REFERENCES devices(id),id TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(device,id))"); err != nil {
		return err
	}
	if _, err = tx.Exec("CREATE INDEX IF NOT EXISTS expired_receipts_expiry ON expired_receipts(expires_at)"); err != nil {
		return err
	}
	// Legacy cleanup receipts used zero for permanent retention. Give existing IDs
	// one full retry window on upgrade, without refreshing it on later opens.
	if _, err = tx.Exec("UPDATE confirmed_receipts SET expires_at=? WHERE expires_at=0", time.Now().Add(domain.PendingLifetime+domain.ReceiptLifetime+domain.ReceiptGrace).UnixMilli()); err != nil {
		return err
	}
	if _, err = tx.Exec("CREATE INDEX IF NOT EXISTS confirmed_receipts_expiry ON confirmed_receipts(expires_at)"); err != nil {
		return err
	}
	if version < 9 {
		var present int
		if err = tx.QueryRow("SELECT count(*) FROM pragma_table_info('messages') WHERE name='bark'").Scan(&present); err != nil {
			return err
		}
		if present == 0 {
			if _, err = tx.Exec("ALTER TABLE messages ADD COLUMN bark TEXT NOT NULL DEFAULT ''"); err != nil {
				return err
			}
		}
	}
	if version < 10 {
		for _, col := range []struct{ name, kind string }{{"application_name", "TEXT NOT NULL DEFAULT ''"}, {"message_group", "TEXT NOT NULL DEFAULT ''"}, {"expires_at", "INTEGER NOT NULL DEFAULT 0"}} {
			var present int
			if err = tx.QueryRow("SELECT count(*) FROM pragma_table_info('messages') WHERE name=?", col.name).Scan(&present); err != nil {
				return err
			}
			if present == 0 {
				if _, err = tx.Exec("ALTER TABLE messages ADD COLUMN " + col.name + " " + col.kind); err != nil {
					return err
				}
			}
		}
		for _, q := range []string{"UPDATE messages SET expires_at=created+604800000 WHERE expires_at=0", "UPDATE messages SET application_name=coalesce((SELECT name FROM applications WHERE id=messages.application_id),'') WHERE application_name=''", "CREATE INDEX IF NOT EXISTS messages_expiry ON messages(acked,expires_at,seq)", "CREATE TABLE IF NOT EXISTS push_requests(credential TEXT NOT NULL,request_key TEXT NOT NULL,fingerprint TEXT NOT NULL,response TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(credential,request_key))", "CREATE INDEX IF NOT EXISTS push_requests_expiry ON push_requests(expires_at)"} {
			if _, err = tx.Exec(q); err != nil {
				return err
			}
		}
	}
	if version < 11 {
		var present int
		if err = tx.QueryRow("SELECT count(*) FROM pragma_table_info('messages') WHERE name='actions'").Scan(&present); err != nil {
			return err
		}
		if present == 0 {
			if _, err = tx.Exec("ALTER TABLE messages ADD COLUMN actions TEXT NOT NULL DEFAULT ''"); err != nil {
				return err
			}
		}
	}
	if _, err = tx.Exec("PRAGMA user_version=" + strconv.Itoa(messageSchemaVersion)); err != nil {
		return err
	}
	return tx.Commit()
}
