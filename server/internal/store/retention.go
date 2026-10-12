package store

import (
	"context"
	"time"

	"beacon.local/server/internal/domain"
)

// Retain minimal confirmation receipts for the bounded offline retry window.
// Unconfirmed messages are never eligible, irrespective of age or device revocation.
func (a *Store) CleanupConfirmedBatch(ctx context.Context, before int64, limit int) (int64, error) {
	if before <= 0 || before > time.Now().UnixMilli() || limit < 1 || limit > domain.MaxRetentionBatchSize {
		return 0, domain.ErrInvalidCutoff
	}
	tx, err := a.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	selection := "SELECT seq FROM messages WHERE acked=1 AND created<? ORDER BY created,seq LIMIT ?"
	if _, err = tx.ExecContext(ctx, "INSERT OR IGNORE INTO confirmed_receipts(device,id,expires_at) SELECT device,id,max(?,expires_at+?) FROM messages WHERE seq IN ("+selection+")", time.Now().Add(domain.ReceiptLifetime).UnixMilli(), (domain.ReceiptLifetime + domain.ReceiptGrace).Milliseconds(), before, limit); err != nil {
		return 0, err
	}
	result, err := tx.ExecContext(ctx, "DELETE FROM messages WHERE seq IN ("+selection+")", before, limit)
	if err != nil {
		return 0, err
	}
	deleted, err := result.RowsAffected()
	if err != nil {
		return 0, err
	}
	if err = tx.Commit(); err != nil {
		return 0, err
	}
	return deleted, nil
}
