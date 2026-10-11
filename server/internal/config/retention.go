package config

import (
	"errors"
	"os"
	"strconv"
	"time"

	"beacon.local/server/internal/domain"
)

const (
	RetentionInterval  = time.Minute
	RetentionTimeout   = 5 * time.Second
	RetentionBatchSize = domain.MaxRetentionBatchSize
)

// Legacy zero maps to one day; confirmed history is always bounded to 30 days.
func RetentionFromEnv() (time.Duration, error) {
	value := os.Getenv("BEACON_HISTORY_RETENTION_DAYS")
	if value == "" {
		return 24 * time.Hour, nil
	}
	days, err := strconv.Atoi(value)
	if err != nil || days < 0 || days > 3650 {
		return 0, errors.New("history retention must be 0 to 3650 whole days")
	}
	if days == 0 {
		days = 1
	}
	if days > 30 {
		days = 30
	}
	return time.Duration(days) * 24 * time.Hour, nil
}
