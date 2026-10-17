package store

import (
	"beacon.local/server/internal/domain"
	"context"
)

func (a *Store) ExpiredMessages(device string) (int64, error) {
	var count int64
	err := a.db.QueryRow("SELECT expired_messages FROM devices WHERE id=? AND revoked=0", device).Scan(&count)
	return count, err
}

// Each transaction retires at most 100 bodies and 100 receipts of each kind.
// A known expired ID can still be confirmed; unknown/cross-device IDs never can.
func (a *Store) ExpireDeliveryBatch(ctx context.Context, now int64) (int64, error) {
	if now <= domain.PendingLifetime.Milliseconds() {
		return 0, domain.ErrInvalidCutoff
	}
	tx, err := a.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	selection := "SELECT seq FROM messages WHERE acked=0 AND expires_at<=? ORDER BY expires_at,seq LIMIT 100"
	cutoff := now
	if _, err = tx.ExecContext(ctx, "INSERT OR IGNORE INTO expired_receipts(device,id,expires_at) SELECT device,id,expires_at+? FROM messages WHERE seq IN ("+selection+")", (domain.ReceiptLifetime + domain.ReceiptGrace).Milliseconds(), cutoff); err != nil {
		return 0, err
	}
	if _, err = tx.ExecContext(ctx, "UPDATE devices SET expired_messages=expired_messages+(SELECT count(*) FROM messages WHERE device=devices.id AND seq IN ("+selection+")) WHERE id IN (SELECT device FROM messages WHERE seq IN ("+selection+"))", cutoff, cutoff); err != nil {
		return 0, err
	}
	result, err := tx.ExecContext(ctx, "DELETE FROM messages WHERE seq IN ("+selection+")", cutoff)
	if err != nil {
		return 0, err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return 0, err
	}
	for _, table := range []string{"confirmed_receipts", "expired_receipts"} {
		if _, err = tx.ExecContext(ctx, "DELETE FROM "+table+" WHERE rowid IN (SELECT rowid FROM "+table+" WHERE expires_at>0 AND expires_at<=? ORDER BY expires_at LIMIT 100)", now); err != nil {
			return 0, err
		}
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM push_requests WHERE rowid IN (SELECT rowid FROM push_requests WHERE expires_at<=? LIMIT 100)", now); err != nil {
		return 0, err
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM binding_codes WHERE rowid IN (SELECT rowid FROM binding_codes WHERE expires_at<=? LIMIT 100)", now); err != nil {
		return 0, err
	}
	return count, tx.Commit()
}
