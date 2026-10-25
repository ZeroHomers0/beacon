package service

import (
	"beacon.local/server/internal/domain"
	"context"
)

func (s *Service) Ready(ctx context.Context) error {
	repository, ok := s.repository.(interface{ Ready(context.Context) error })
	if !ok {
		return domain.ErrRealtimeUnavailable
	}
	return repository.Ready(ctx)
}
func (s *Service) OperationalStats(ctx context.Context) (domain.OperationalStats, error) {
	repository, ok := s.repository.(interface {
		OperationalStats(context.Context) (domain.OperationalStats, error)
	})
	if !ok {
		return domain.OperationalStats{}, domain.ErrRealtimeUnavailable
	}
	return repository.OperationalStats(ctx)
}
