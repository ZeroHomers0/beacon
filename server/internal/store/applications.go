package store

import (
	"database/sql"
	"errors"
	"strings"
	"unicode/utf8"

	"beacon.local/server/internal/auth"
	"beacon.local/server/internal/domain"
)

func (a *Store) CreateApplication(device, name string, priority int) (domain.ApplicationCredential, error) {
	name = strings.TrimSpace(name)
	if name == "" || len(name) > domain.MaxDeviceNameBytes || !utf8.ValidString(name) || priority < 0 || priority > 10 {
		return domain.ApplicationCredential{}, domain.ErrInvalidMessage
	}
	tx, err := a.db.Begin()
	if err != nil {
		return domain.ApplicationCredential{}, err
	}
	defer tx.Rollback()
	var total int
	if err = tx.QueryRow("SELECT count(*) FROM applications").Scan(&total); err != nil {
		return domain.ApplicationCredential{}, err
	}
	if total >= 4096 {
		return domain.ApplicationCredential{}, domain.ErrStorageCapacity
	}
	id, err := auth.NewToken()
	if err != nil {
		return domain.ApplicationCredential{}, err
	}
	token, err := auth.NewToken()
	if err != nil {
		return domain.ApplicationCredential{}, err
	}
	result, err := tx.Exec(`INSERT INTO applications(id,device,name,token_hash,default_priority) SELECT ?,id,?,?,? FROM devices WHERE id=? AND revoked=0`, id, name, auth.HashToken(token), priority, device)
	if err != nil {
		return domain.ApplicationCredential{}, err
	}
	count, err := result.RowsAffected()
	if err != nil || count != 1 {
		return domain.ApplicationCredential{}, domain.ErrInvalidDevice
	}
	if err = tx.Commit(); err != nil {
		return domain.ApplicationCredential{}, err
	}
	return domain.ApplicationCredential{Application: domain.Application{ID: id, DeviceID: device, Name: name, DefaultPriority: priority}, Token: token}, nil
}

func (a *Store) ListApplications() ([]domain.Application, error) {
	rows, err := a.db.Query(`SELECT a.id,a.device,a.name,a.default_priority,(a.revoked OR d.revoked) FROM applications a JOIN devices d ON d.id=a.device ORDER BY a.name,a.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []domain.Application{}
	for rows.Next() {
		var item domain.Application
		if err := rows.Scan(&item.ID, &item.DeviceID, &item.Name, &item.DefaultPriority, &item.Revoked); err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}

func (a *Store) RevokeApplication(id string) error {
	result, err := a.db.Exec("UPDATE applications SET revoked=1 WHERE id=?", id)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil || count != 1 {
		return domain.ErrInvalidDevice
	}
	return nil
}

// DeleteApplication permanently removes a revoked sending application together with the messages
// it sent. Only the application's own messages go: a message pushed with the device key has no
// application, and one sent by another application of the same device stays. Active applications
// are rejected, mirroring device deletion.
//
// A message already retired by retention leaves only a minimal receipt (device, id, expiry) with
// no application on it, so a shared device's receipts cannot be attributed to one sender and are
// left to age out inside the receipt window rather than guessed at.
func (a *Store) DeleteApplication(id string) (int64, error) {
	tx, err := a.db.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	var tokenHash string
	if err = tx.QueryRow("SELECT token_hash FROM applications WHERE id=? AND revoked=1", id).Scan(&tokenHash); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return 0, domain.ErrInvalidDevice
		}
		return 0, err
	}
	// Deleting the messages cascades to their queued realtime publications.
	result, err := tx.Exec("DELETE FROM messages WHERE application_id=?", id)
	if err != nil {
		return 0, err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return 0, err
	}
	// Idempotency records are keyed by the credential hash, which the application row still holds.
	if _, err = tx.Exec("DELETE FROM push_requests WHERE credential=?", tokenHash); err != nil {
		return 0, err
	}
	if err = deleteApplicationStates(tx, id); err != nil {
		return 0, err
	}
	if _, err = tx.Exec("DELETE FROM applications WHERE id=?", id); err != nil {
		return 0, err
	}
	return count, tx.Commit()
}

func (a *Store) ApplicationForToken(token string) (domain.Application, error) {
	var item domain.Application
	if len(token) != domain.TokenLength {
		return item, domain.ErrUnauthorized
	}
	err := a.db.QueryRow(`SELECT a.id,a.device,a.name,a.default_priority FROM applications a JOIN devices d ON d.id=a.device WHERE a.token_hash=? AND a.revoked=0 AND d.revoked=0`, auth.HashToken(token)).Scan(&item.ID, &item.DeviceID, &item.Name, &item.DefaultPriority)
	if err != nil {
		return item, domain.ErrUnauthorized
	}
	return item, nil
}
