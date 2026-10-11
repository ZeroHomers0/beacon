package service

import (
	"context"
	"time"
)

type DeliveryExpirationStore interface {
	ExpireDeliveryBatch(context.Context, int64) (int64, error)
}

func RunDeliveryExpiration(ctx context.Context, repository DeliveryExpirationStore, observe func(error)) {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		if ctx.Err() != nil {
			return
		}
		batch, cancel := context.WithTimeout(ctx, 5*time.Second)
		_, err := repository.ExpireDeliveryBatch(batch, time.Now().UnixMilli())
		cancel()
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

func (s *Service) ExpiredMessages(device string) (int64, error) {
	if repository, ok := s.repository.(interface{ ExpiredMessages(string) (int64, error) }); ok {
		return repository.ExpiredMessages(device)
	}
	return 0, nil
}
