package service

import (
	"beacon.local/server/internal/domain"
	"context"
)

type MaintenanceRepository interface {
	Ready(context.Context) error
	OperationalStats(context.Context) (domain.OperationalStats, error)
	ClearMessages(string, bool) (int64, error)
	ReceiptStatuses(string, []string) (map[string]string, error)
	ExpiredMessages(string) (int64, error)
}

func (s *Service) Ready(ctx context.Context) error { return s.repository.Ready(ctx) }
func (s *Service) OperationalStats(ctx context.Context) (domain.OperationalStats, error) {
	return s.repository.OperationalStats(ctx)
}
