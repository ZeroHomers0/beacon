package store

import (
	"beacon.local/server/internal/domain"
	"context"
	"time"
)

func (a *Store) Ready(ctx context.Context) error {
	var value int
	return a.db.QueryRowContext(ctx, "SELECT 1").Scan(&value)
}

// The caller caches this bounded aggregate; a deadline prevents monitoring from queuing forever.
func (a *Store) OperationalStats(ctx context.Context) (domain.OperationalStats, error) {
	now := time.Now().UnixMilli()
	result := domain.OperationalStats{SampledAt: now}
	var oldest int64
	err := a.db.QueryRowContext(ctx, `SELECT count(*),coalesce(min(m.created),0),coalesce(sum(o.attempts>0),0)
        FROM publish_outbox o JOIN messages m ON m.seq=o.message_seq`).Scan(&result.Queued, &oldest, &result.Retrying)
	if oldest > 0 && oldest < now {
		result.OldestAge = now - oldest
	}
	return result, err
}
