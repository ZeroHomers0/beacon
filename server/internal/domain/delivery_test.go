package domain

import (
	"testing"
	"time"
)

func TestPublishRetryDelayIsBounded(t *testing.T) {
	for _, test := range []struct {
		attempt int
		delay   time.Duration
	}{
		{1, 5 * time.Second}, {2, 10 * time.Second}, {6, 160 * time.Second}, {7, 5 * time.Minute}, {30, 5 * time.Minute},
	} {
		if got := PublishRetryDelay(test.attempt); got != test.delay {
			t.Fatalf("attempt %d: %v", test.attempt, got)
		}
	}
}
