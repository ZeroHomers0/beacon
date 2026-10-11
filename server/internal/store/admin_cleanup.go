package store

import (
	"beacon.local/server/internal/domain"
	"database/sql"
	"errors"
)

// ClearMessages intentionally discards pending delivery as well as receipt history.
func (a *Store) ClearMessages(device string, deleteDevice bool) (int64, error) {
	tx, err := a.db.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	if deleteDevice {
		var revoked bool
		if err = tx.QueryRow("SELECT revoked FROM devices WHERE id=?", device).Scan(&revoked); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return 0, domain.ErrInvalidDevice
			}
			return 0, err
		}
		if !revoked {
			return 0, domain.ErrInvalidDevice
		}
	}
	where := ""
	args := []any{}
	if device != "" {
		where = " WHERE device=?"
		args = append(args, device)
	}
	for _, table := range []string{"confirmed_receipts", "expired_receipts"} {
		if _, err = tx.Exec("DELETE FROM "+table+where, args...); err != nil {
			return 0, err
		}
	}
	result, err := tx.Exec("DELETE FROM messages"+where, args...)
	if err != nil {
		return 0, err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return 0, err
	}
	if deleteDevice {
		for _, table := range []string{"applications", "session_disconnects"} {
			if _, err = tx.Exec("DELETE FROM "+table+" WHERE device=?", device); err != nil {
				return 0, err
			}
		}
		if _, err = tx.Exec("DELETE FROM devices WHERE id=?", device); err != nil {
			return 0, err
		}
	}
	return count, tx.Commit()
}
