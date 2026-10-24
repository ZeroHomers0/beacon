package store

import (
	"database/sql"
	"encoding/json"

	"beacon.local/server/internal/auth"
	"beacon.local/server/internal/domain"
)

// ClaimPublish leases a bounded batch. Network I/O must happen after this transaction ends.
// Expired leases are reclaimed after a worker crash, so duplicate publication is possible.
func (a *Store) ClaimPublish(now int64, limit int) ([]domain.PublishTask, error) {
	if now <= 0 || limit < 1 || limit > domain.PublishBatchSize {
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
	rows, err := tx.Query(`SELECT m.device,m.id,m.seq,m.title,m.body,m.created,m.subtitle,o.attempts,m.priority,m.application_id,m.bark,m.message_group,m.expires_at,m.application_name,m.actions,m.blocks,m.schema_version
		FROM publish_outbox o JOIN messages m ON m.seq=o.message_seq JOIN devices d ON d.id=m.device
		WHERE o.next_attempt<=? AND o.lease_until<=? AND m.acked=0 AND d.revoked=0
		AND m.expires_at>?
		ORDER BY o.next_attempt,m.seq LIMIT ?`, now, now, now, limit)
	if err != nil {
		return nil, err
	}
	tasks := make([]domain.PublishTask, 0, limit)
	for rows.Next() {
		var actions, blocks string
		task := domain.PublishTask{ServerID: a.serverID, LeaseToken: lease, LeaseUntil: now + domain.PublishLease.Milliseconds()}
		if err = rows.Scan(&task.DeviceID, &task.Message.ID, &task.Message.Sequence,
			&task.Message.Title, &task.Message.Body, &task.Message.CreatedAt,
			&task.Message.Subtitle, &task.Attempts, &task.Message.Priority, &task.Message.ApplicationID, &task.Message.Bark, &task.Message.Group, &task.Message.ExpiresAt, &task.Message.ApplicationName, &actions, &blocks, &task.Message.SchemaVersion); err != nil {
			rows.Close()
			return nil, err
		}
		if blocks != "" {
			if err := json.Unmarshal([]byte(blocks), &task.Message.Blocks); err != nil {
				rows.Close()
				return nil, err
			}
		}
		if actions != "" {
			if err := json.Unmarshal([]byte(actions), &task.Message.Actions); err != nil {
				rows.Close()
				return nil, err
			}
		}
		if task.Attempts < domain.PublishAttemptLimit {
			task.Attempts++
		}
		tasks = append(tasks, task)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	for _, task := range tasks {
		if _, err = tx.Exec(`UPDATE publish_outbox SET lease_token=?,lease_until=?,attempts=?
			WHERE message_seq=?`, lease, now+domain.PublishLease.Milliseconds(),
			task.Attempts, task.Message.Sequence); err != nil {
			return nil, err
		}
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return tasks, nil
}

// Recheck before handing a task to the network; receipts and revocation cancel queued work.
func (a *Store) Publishable(task domain.PublishTask, now int64) (bool, error) {
	var count int
	err := a.db.QueryRow(`SELECT count(*) FROM publish_outbox o
		JOIN messages m ON m.seq=o.message_seq JOIN devices d ON d.id=m.device
		WHERE o.message_seq=? AND o.lease_token=? AND o.lease_until>?
		AND m.acked=0 AND d.revoked=0
		AND m.expires_at>?`, task.Message.Sequence, task.LeaseToken, now, now).Scan(&count)
	return count == 1, err
}

// CompletePublish returns false for an expired/replaced lease or a receipt/revocation.
// Successful WebSocket write retires only the task, never the message or its receipt state.
func (a *Store) CompletePublish(task domain.PublishTask, success bool, now int64) (bool, error) {
	if now <= 0 || task.Message.Sequence <= 0 || len(task.LeaseToken) != domain.TokenLength {
		return false, domain.ErrInvalidDelivery
	}
	tx, err := a.db.Begin()
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	var attempts int
	err = tx.QueryRow(`SELECT attempts FROM publish_outbox
		WHERE message_seq=? AND lease_token=? AND lease_until>?`,
		task.Message.Sequence, task.LeaseToken, now).Scan(&attempts)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	var result sql.Result
	if success {
		result, err = tx.Exec("DELETE FROM publish_outbox WHERE message_seq=? AND lease_token=?",
			task.Message.Sequence, task.LeaseToken)
	} else {
		// Use the persisted attempt count, not caller-supplied state, for retry scheduling.
		result, err = tx.Exec(`UPDATE publish_outbox SET next_attempt=?,lease_token='',lease_until=0
			WHERE message_seq=? AND lease_token=?`, now+domain.PublishRetryDelay(attempts).Milliseconds(),
			task.Message.Sequence, task.LeaseToken)
	}
	if err != nil {
		return false, err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return false, err
	}
	return count == 1, tx.Commit()
}
