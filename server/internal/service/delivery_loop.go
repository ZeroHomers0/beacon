package service

import (
	"context"
	"time"
)

// RunDelivery owns one serial worker; the database controls per-message retry deadlines.
func RunDelivery(ctx context.Context, queue DeliveryQueue, publisher Publisher, interval time.Duration, observe func(DeliveryResult, error)) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		result, err := DrainDelivery(ctx, queue, publisher, 1)
		if observe != nil && (err != nil || result.Claimed > 0) {
			observe(result, err)
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}
