package store

import (
	"beacon.local/server/internal/domain"
	"time"
)

// SQLite page counts describe allocated and reusable database pages, not WAL size.
func (a *Store) StorageStats() (domain.StorageStats, error) {
	tx, err := a.db.Begin()
	if err != nil {
		return domain.StorageStats{}, err
	}
	defer tx.Rollback()
	var stats domain.StorageStats
	if err = tx.QueryRow("SELECT coalesce(sum(deleted=0),0),coalesce(sum(length(CAST(payload AS BLOB))*(deleted=0)),0),count(*) FROM current_states").Scan(&stats.States, &stats.StateBytes, &stats.StateWatermarks); err != nil {
		return stats, err
	}
	if err = tx.QueryRow("SELECT coalesce(min(CASE WHEN acked=0 THEN created END),0),coalesce(min(CASE WHEN acked=1 THEN created END),0) FROM messages").Scan(&stats.OldestPendingMS, &stats.OldestConfirmedMS); err != nil {
		return domain.StorageStats{}, err
	}
	err = tx.QueryRow("SELECT count(*),coalesce(sum(acked=0),0),coalesce(sum(acked=1),0) FROM messages").
		Scan(&stats.Messages, &stats.Pending, &stats.Confirmed)
	if err != nil {
		return domain.StorageStats{}, err
	}
	if err = tx.QueryRow("SELECT count(*) FROM devices").Scan(&stats.Devices); err != nil {
		return domain.StorageStats{}, err
	}
	if err = tx.QueryRow("SELECT count(*) FROM confirmed_receipts").Scan(&stats.Receipts); err != nil {
		return domain.StorageStats{}, err
	}
	if err = tx.QueryRow("SELECT count(*) FROM publish_outbox").Scan(&stats.QueuedRealtime); err != nil {
		return domain.StorageStats{}, err
	}
	if err = tx.QueryRow("SELECT count(*) FROM session_disconnects").Scan(&stats.QueuedDisconnects); err != nil {
		return domain.StorageStats{}, err
	}
	var pages, freePages, pageSize int64
	for _, query := range []struct {
		sql string
		out *int64
	}{
		{"PRAGMA page_count", &pages},
		{"PRAGMA freelist_count", &freePages},
		{"PRAGMA page_size", &pageSize},
	} {
		if err = tx.QueryRow(query.sql).Scan(query.out); err != nil {
			return domain.StorageStats{}, err
		}
	}
	stats.DatabaseBytes = pages * pageSize
	stats.ReusableBytes = freePages * pageSize
	return stats, tx.Commit()
}

// Preview and deletion share a transaction; only server-confirmed history is eligible.
// No VACUUM is performed: freed pages remain available for subsequent writes.
func (a *Store) CleanupConfirmed(before int64, apply bool) (domain.CleanupResult, error) {
	tx, err := a.db.Begin()
	if err != nil {
		return domain.CleanupResult{}, err
	}
	defer tx.Rollback()
	result := domain.CleanupResult{BeforeMS: before, Applied: apply}
	err = tx.QueryRow("SELECT coalesce(sum(acked=1),0),coalesce(sum(acked=0),0) FROM messages WHERE created<?", before).
		Scan(&result.Eligible, &result.ProtectedPending)
	if err != nil {
		return domain.CleanupResult{}, err
	}
	if apply {
		// Keep only IDs for idempotent confirmation retries after an HTTP response was lost.
		if _, err = tx.Exec("INSERT OR IGNORE INTO confirmed_receipts(device,id,expires_at) SELECT device,id,max(?,expires_at+?) FROM messages WHERE created<? AND acked=1", time.Now().Add(domain.ReceiptLifetime).UnixMilli(), (domain.ReceiptLifetime + domain.ReceiptGrace).Milliseconds(), before); err != nil {
			return domain.CleanupResult{}, err
		}
		deleted, err := tx.Exec("DELETE FROM messages WHERE created<? AND acked=1", before)
		if err != nil {
			return domain.CleanupResult{}, err
		}
		if result.Deleted, err = deleted.RowsAffected(); err != nil {
			return domain.CleanupResult{}, err
		}
	}
	return result, tx.Commit()
}
