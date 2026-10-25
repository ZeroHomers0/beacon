package service

import (
	"context"
	"time"

	"beacon.local/server/internal/domain"
)

type DeliveryQueue interface {
	ClaimPublish(int64, int) ([]domain.PublishTask, error)
	Publishable(domain.PublishTask, int64) (bool, error)
	CompletePublish(domain.PublishTask, bool, int64) (bool, error)
}

// Publisher must respect cancellation and return success only after receiver socket write.
// Credentials, leases and retry state are not exposed to the publisher.
type Publisher interface {
	Publish(context.Context, string, string, domain.Message) error
}

type DeliveryResult struct {
	Claimed   int
	Published int
	Retrying  int
	Skipped   int
}

// DrainDelivery performs one bounded pass. It does not own a background loop or HTTP receipt.
func DrainDelivery(ctx context.Context, queue DeliveryQueue, publisher Publisher, limit int) (DeliveryResult, error) {
	result := DeliveryResult{}
	if err := ctx.Err(); err != nil {
		return result, err
	}
	tasks, err := queue.ClaimPublish(time.Now().UnixMilli(), limit)
	if err != nil {
		return result, err
	}
	return drainClaimed(ctx, queue, publisher, tasks)
}

func drainClaimed(ctx context.Context, queue DeliveryQueue, publisher Publisher, tasks []domain.PublishTask) (DeliveryResult, error) {
	result := DeliveryResult{Claimed: len(tasks)}
	for _, task := range tasks {
		if err := ctx.Err(); err != nil {
			// Remaining leases expire naturally; shutdown never deletes unprocessed work.
			return result, err
		}
		allowed, err := queue.Publishable(task, time.Now().UnixMilli())
		if err != nil {
			return result, err
		}
		if !allowed {
			result.Skipped++
			continue
		}
		// Reserve time to persist completion before the lease expires.
		deadline := time.UnixMilli(task.LeaseUntil).Add(-domain.PublishCompletionReserve)
		if !time.Now().Before(deadline) {
			result.Skipped++
			continue
		}
		publishCtx, cancel := context.WithDeadline(ctx, deadline)
		publishErr := publisher.Publish(publishCtx, task.ServerID, task.DeviceID, task.Message)
		cancel()
		completed, err := queue.CompletePublish(task, publishErr == nil, time.Now().UnixMilli())
		if err != nil {
			return result, err
		}
		if !completed {
			result.Skipped++
		} else if publishErr == nil {
			result.Published++
		} else {
			result.Retrying++
		}
	}
	return result, nil
}
