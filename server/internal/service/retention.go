package service

import (
	"context"
	"time"

	"beacon.local/server/internal/domain"
)

type HistoryRetentionStore interface {
	CleanupConfirmedBatch(context.Context, int64, int) (int64, error)
}

// One bounded transaction per tick. Cancellation cannot leave half a receipt/message pair.
func RunHistoryRetention(ctx context.Context, repository HistoryRetentionStore, age, interval, timeout time.Duration, batchSize int, observe func(int64, error)) {
	if age <= 0 || interval <= 0 || timeout <= 0 || batchSize < 1 || batchSize > domain.MaxRetentionBatchSize {
		return
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		if ctx.Err() != nil {
			return
		}
		cutoff := time.Now().Add(-age).UnixMilli()
		if cutoff > 0 {
			batch, cancel := context.WithTimeout(ctx, timeout)
			deleted, err := repository.CleanupConfirmedBatch(batch, cutoff, batchSize)
			cancel()
			if ctx.Err() == nil && observe != nil && (deleted > 0 || err != nil) {
				observe(deleted, err)
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}
