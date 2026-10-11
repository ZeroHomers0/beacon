package store

import (
	"database/sql"

	"beacon.local/server/internal/auth"
	"beacon.local/server/internal/domain"
)

func enqueueDisconnect(tx *sql.Tx, device string, now int64) error {
	var fingerprint string
	if err := tx.QueryRow("SELECT receive_hash FROM devices WHERE id=?", device).Scan(&fingerprint); err != nil {
		return err
	}
	_, err := tx.Exec(`INSERT INTO session_disconnects(client_id,device,until_ms) VALUES(?,?,?)
		ON CONFLICT(client_id) DO UPDATE SET until_ms=max(until_ms,excluded.until_ms)`,
		domain.ReceiverClientID(device, fingerprint), device, now+domain.SessionDisconnectGuard.Milliseconds())
	return err
}

// A single task per pass bounds network work; leases survive process crashes.
func (a *Store) ClaimDisconnect(now int64) (*domain.DisconnectTask, error) {
	if now <= 0 {
		return nil, domain.ErrInvalidDelivery
	}
	lease, err := auth.NewToken()
	if err != nil {
		return nil, err
	}
	tx, err := a.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	task := &domain.DisconnectTask{LeaseToken: lease, LeaseUntil: now + domain.PublishLease.Milliseconds()}
	err = tx.QueryRow(`SELECT client_id FROM session_disconnects WHERE next_attempt<=? AND lease_until<=?
		ORDER BY next_attempt,client_id LIMIT 1`, now, now).Scan(&task.ClientID)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	_, err = tx.Exec(`UPDATE session_disconnects SET lease_token=?,lease_until=?,attempts=min(attempts+1,?) WHERE client_id=?`, lease, task.LeaseUntil, domain.PublishAttemptLimit, task.ClientID)
	if err != nil {
		return nil, err
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return task, nil
}

// Success before the guard expires schedules another kick instead of clearing the barrier.
// Only a final confirmed disconnect (or confirmed absent client) releases publication.
func (a *Store) CompleteDisconnect(task domain.DisconnectTask, success bool, now int64) (bool, error) {
	if now <= 0 || len(task.LeaseToken) != domain.TokenLength {
		return false, domain.ErrInvalidDelivery
	}
	tx, err := a.db.Begin()
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	var attempts int
	var until int64
	err = tx.QueryRow(`SELECT attempts,until_ms FROM session_disconnects WHERE client_id=? AND lease_token=? AND lease_until>?`, task.ClientID, task.LeaseToken, now).Scan(&attempts, &until)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if success && now >= until {
		_, err = tx.Exec("DELETE FROM session_disconnects WHERE client_id=? AND lease_token=?", task.ClientID, task.LeaseToken)
	} else {
		delay := domain.PublishRetryDelay(attempts)
		if success {
			delay = domain.SessionDisconnectRepeat
		}
		_, err = tx.Exec(`UPDATE session_disconnects SET next_attempt=?,lease_until=0,lease_token='' WHERE client_id=? AND lease_token=?`, now+delay.Milliseconds(), task.ClientID, task.LeaseToken)
	}
	if err != nil {
		return false, err
	}
	return true, tx.Commit()
}
