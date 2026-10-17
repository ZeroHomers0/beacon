package store

import (
	"database/sql"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"beacon.local/server/internal/auth"
	"beacon.local/server/internal/domain"
)

// Only hashes are persisted. The administrator delivers the code out of band.
func (a *Store) CreateBindingCode(name string, ttl time.Duration) (domain.BindingCode, error) {
	name = strings.TrimSpace(name)
	if name == "" || len(name) > domain.MaxDeviceNameBytes || !utf8.ValidString(name) || ttl < time.Minute || ttl > 24*time.Hour {
		return domain.BindingCode{}, domain.ErrInvalidBinding
	}
	token, err := auth.NewToken()
	if err != nil {
		return domain.BindingCode{}, err
	}
	now := time.Now().UnixMilli()
	code := domain.BindingCode{Code: token[:domain.BindingCodeLength], DeviceName: name, ExpiresAt: now + ttl.Milliseconds()}
	tx, err := a.db.Begin()
	if err != nil {
		return domain.BindingCode{}, err
	}
	defer tx.Rollback()
	if _, err = tx.Exec("DELETE FROM binding_codes WHERE expires_at<=?", now); err != nil {
		return domain.BindingCode{}, err
	}
	var count int
	if err = tx.QueryRow("SELECT count(*) FROM binding_codes").Scan(&count); err != nil {
		return domain.BindingCode{}, err
	}
	if count >= 1000 {
		return domain.BindingCode{}, domain.ErrStorageCapacity
	}
	if _, err = tx.Exec("INSERT INTO binding_codes(code_hash,device_name,expires_at) VALUES(?,?,?)", auth.HashToken(code.Code), name, code.ExpiresAt); err != nil {
		return domain.BindingCode{}, err
	}
	return code, tx.Commit()
}

// Consuming the code and creating the device are one transaction, including racing requests.
func (a *Store) RedeemBindingCode(code, name string) (domain.DeviceCredentials, error) {
	if len(code) != domain.BindingCodeLength || len(name) > domain.MaxDeviceNameBytes || !utf8.ValidString(name) {
		return domain.DeviceCredentials{}, domain.ErrInvalidBinding
	}
	tx, err := a.db.Begin()
	if err != nil {
		return domain.DeviceCredentials{}, err
	}
	defer tx.Rollback()
	var storedName string
	var count int
	if err = tx.QueryRow("SELECT count(*) FROM devices").Scan(&count); err != nil {
		return domain.DeviceCredentials{}, err
	}
	if count >= 1024 {
		return domain.DeviceCredentials{}, domain.ErrStorageCapacity
	}
	now := time.Now().UnixMilli()
	err = tx.QueryRow("SELECT device_name FROM binding_codes WHERE code_hash=? AND used_at IS NULL AND expires_at>?", auth.HashToken(code), now).Scan(&storedName)
	if errors.Is(err, sql.ErrNoRows) {
		return domain.DeviceCredentials{}, domain.ErrInvalidBinding
	}
	if err != nil {
		return domain.DeviceCredentials{}, err
	}
	if name == "" {
		name = storedName
	}
	credentials := domain.DeviceCredentials{ServerID: a.serverID}
	if credentials.DeviceID, err = auth.NewToken(); err != nil {
		return domain.DeviceCredentials{}, err
	}
	if credentials.ReceiveToken, err = auth.NewToken(); err != nil {
		return domain.DeviceCredentials{}, err
	}
	if credentials.PushKey, err = auth.NewToken(); err != nil {
		return domain.DeviceCredentials{}, err
	}
	result, err := tx.Exec("UPDATE binding_codes SET used_at=? WHERE code_hash=? AND used_at IS NULL AND expires_at>?", now, auth.HashToken(code), now)
	if err != nil {
		return domain.DeviceCredentials{}, err
	}
	if count, err := result.RowsAffected(); err != nil || count != 1 {
		return domain.DeviceCredentials{}, domain.ErrInvalidBinding
	}
	if _, err = tx.Exec("INSERT INTO devices(id,name,receive_hash,push_hash) VALUES(?,?,?,?)", credentials.DeviceID, name, auth.HashToken(credentials.ReceiveToken), auth.HashToken(credentials.PushKey)); err != nil {
		return domain.DeviceCredentials{}, err
	}
	if err = tx.Commit(); err != nil {
		return domain.DeviceCredentials{}, err
	}
	return credentials, nil
}

func (a *Store) RevokeDevice(id string) error {
	tx, err := a.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err = revokeDeviceTx(tx, id); err != nil {
		return err
	}
	return tx.Commit()
}

// Credential lookup and revocation share the transaction, closing the rotation race.
// Revoked hashes remain available only here to make a lost response retry idempotent.
func (a *Store) RevokeReceiver(token, id string) error {
	tx, err := a.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var matches int
	if err = tx.QueryRow("SELECT count(*) FROM devices WHERE id=? AND receive_hash=?", id, auth.HashToken(token)).Scan(&matches); err != nil {
		return err
	}
	if matches != 1 {
		return domain.ErrUnauthorized
	}
	if err = revokeDeviceTx(tx, id); err != nil {
		return err
	}
	return tx.Commit()
}

func revokeDeviceTx(tx *sql.Tx, id string) error {
	var revoked bool
	if err := tx.QueryRow("SELECT revoked FROM devices WHERE id=?", id).Scan(&revoked); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return domain.ErrInvalidDevice
		}
		return err
	}
	if !revoked {
		if err := enqueueDisconnect(tx, id, time.Now().UnixMilli()); err != nil {
			return err
		}
	}
	result, err := tx.Exec("UPDATE devices SET revoked=1 WHERE id=?", id)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return domain.ErrInvalidDevice
	}
	if _, err := tx.Exec(`DELETE FROM publish_outbox WHERE message_seq IN
		(SELECT seq FROM messages WHERE device=?)`, id); err != nil {
		return err
	}
	return nil
}

// Replace both hashes atomically without changing identity or message ownership.
// Revoked devices cannot be reactivated by rotating their credentials.
func (a *Store) RotateDevice(id string) (domain.DeviceCredentials, error) {
	credentials := domain.DeviceCredentials{ServerID: a.serverID, DeviceID: id}
	var err error
	if credentials.ReceiveToken, err = auth.NewToken(); err != nil {
		return domain.DeviceCredentials{}, err
	}
	if credentials.PushKey, err = auth.NewToken(); err != nil {
		return domain.DeviceCredentials{}, err
	}
	tx, err := a.db.Begin()
	if err != nil {
		return domain.DeviceCredentials{}, err
	}
	defer tx.Rollback()
	var active int
	if err = tx.QueryRow("SELECT count(*) FROM devices WHERE id=? AND revoked=0", id).Scan(&active); err != nil {
		return domain.DeviceCredentials{}, err
	}
	if active != 1 {
		return domain.DeviceCredentials{}, domain.ErrInvalidDevice
	}
	if err = enqueueDisconnect(tx, id, time.Now().UnixMilli()); err != nil {
		return domain.DeviceCredentials{}, err
	}
	result, err := tx.Exec("UPDATE devices SET receive_hash=?,push_hash=? WHERE id=? AND revoked=0",
		auth.HashToken(credentials.ReceiveToken), auth.HashToken(credentials.PushKey), id)
	if err != nil {
		return domain.DeviceCredentials{}, err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return domain.DeviceCredentials{}, err
	}
	if count != 1 {
		return domain.DeviceCredentials{}, domain.ErrInvalidDevice
	}
	if err = tx.Commit(); err != nil {
		return domain.DeviceCredentials{}, err
	}
	return credentials, nil
}

func (a *Store) ListDevices() ([]domain.DeviceSummary, error) {
	rows, err := a.db.Query("SELECT id,name,revoked FROM devices ORDER BY name,id")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	devices := make([]domain.DeviceSummary, 0)
	for rows.Next() {
		var device domain.DeviceSummary
		if err = rows.Scan(&device.DeviceID, &device.Name, &device.Revoked); err != nil {
			return nil, err
		}
		devices = append(devices, device)
	}
	return devices, rows.Err()
}
