package service

import (
	"beacon.local/server/internal/domain"
	"context"
	"errors"
	"time"
)

// A round claims at most four tasks, so no large batch waits in memory for a worker/lease.
const deliveryParallelism = 4

// DrainDeliveryIsolated keeps each device ordered and isolates other devices' socket waits.
// The serial primitive remains available for callers requiring one explicit global order.
func DrainDeliveryIsolated(ctx context.Context, queue DeliveryQueue, publisher Publisher, limit int) (DeliveryResult, error) {
	if limit < 1 || limit > domain.PublishBatchSize {
		return DeliveryResult{}, domain.ErrInvalidDelivery
	}
	if err := ctx.Err(); err != nil {
		return DeliveryResult{}, err
	}
	tasks, err := queue.ClaimPublish(time.Now().UnixMilli(), min(limit, deliveryParallelism))
	if err != nil {
		return DeliveryResult{}, err
	}
	groups := make(map[string][]domain.PublishTask)
	for _, task := range tasks {
		groups[task.DeviceID] = append(groups[task.DeviceID], task)
	}
	type completion struct {
		result DeliveryResult
		err    error
	}
	completed := make(chan completion, len(groups))
	for _, batch := range groups {
		go func() { r, e := drainClaimed(ctx, queue, publisher, batch); completed <- completion{r, e} }()
	}
	result := DeliveryResult{}
	var failures []error
	for range groups {
		item := <-completed
		result.Claimed += item.result.Claimed
		result.Published += item.result.Published
		result.Retrying += item.result.Retrying
		result.Skipped += item.result.Skipped
		if item.err != nil {
			failures = append(failures, item.err)
		}
	}
	return result, errors.Join(failures...)
}
