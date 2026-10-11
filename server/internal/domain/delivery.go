package domain

import "time"

const (
	PublishBatchSize         = 100
	PublishLease             = 30 * time.Second
	PublishRetryMinimum      = 5 * time.Second
	PublishRetryMaximum      = 5 * time.Minute
	PublishAttemptLimit      = 30
	PublishCompletionReserve = time.Second
)

// A lease is internal state, never a device credential or part of a wire envelope.
type PublishTask struct {
	DeviceID   string
	ServerID   string
	Message    Message
	LeaseToken string
	LeaseUntil int64
	Attempts   int
}

// Retry indefinitely with a bounded delay; a crash also counts as an attempt.
func PublishRetryDelay(attempts int) time.Duration {
	delay := PublishRetryMinimum
	for i := 1; i < attempts && delay < PublishRetryMaximum; i++ {
		delay *= 2
	}
	if delay > PublishRetryMaximum {
		return PublishRetryMaximum
	}
	return delay
}
