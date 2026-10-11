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

// Zero preserves all history. A single optional setting controls automatic cleanup.
func RetentionFromEnv() (time.Duration, error) {
	value := os.Getenv("BEACON_HISTORY_RETENTION_DAYS")
	if value == "" {
		return 0, nil
	}
	days, err := strconv.Atoi(value)
	if err != nil || days < 0 || days > 3650 {
		return 0, errors.New("history retention must be 0 to 3650 whole days")
	}
	return time.Duration(days) * 24 * time.Hour, nil
}
