package service

import (
	"context"
	"time"

	"beacon.local/server/internal/domain"
)

type DisconnectQueue interface {
	ClaimDisconnect(int64) (*domain.DisconnectTask, error)
	CompleteDisconnect(domain.DisconnectTask, bool, int64) (bool, error)
}
type Disconnector interface {
	Disconnect(context.Context, string) error
}

func DrainDisconnect(ctx context.Context, queue DisconnectQueue, transport Disconnector) (bool, error) {
	if err := ctx.Err(); err != nil {
		return false, err
	}
	task, err := queue.ClaimDisconnect(time.Now().UnixMilli())
	if err != nil || task == nil {
		return false, err
	}
	deadline := time.UnixMilli(task.LeaseUntil).Add(-domain.PublishCompletionReserve)
	requestCtx, cancel := context.WithDeadline(ctx, deadline)
	disconnectErr := transport.Disconnect(requestCtx, task.ClientID)
	cancel()
	_, err = queue.CompleteDisconnect(*task, disconnectErr == nil, time.Now().UnixMilli())
	if err != nil {
		return true, err
	}
	return true, disconnectErr
}

func RunDisconnect(ctx context.Context, queue DisconnectQueue, transport Disconnector, interval time.Duration, observe func(error)) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		_, err := DrainDisconnect(ctx, queue, transport)
		if err != nil && ctx.Err() == nil && observe != nil {
			observe(err)
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}
