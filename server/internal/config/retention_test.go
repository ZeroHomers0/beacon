package config

import (
	"testing"
	"time"
)

func TestRetentionConfiguration(t *testing.T) {
	for value, days := range map[string]int{"": 1, "0": 1, "1": 1, "30": 30, "3650": 30} {
		t.Run("valid_"+value, func(t *testing.T) {
			t.Setenv("BEACON_HISTORY_RETENTION_DAYS", value)
			age, err := RetentionFromEnv()
			if err != nil || age != time.Duration(days)*24*time.Hour {
				t.Fatalf("unexpected duration %v: %v", age, err)
			}
		})
	}
	for _, value := range []string{"-1", "3651", "1.5", " 30", "30d", "secret", "999999999999999999999999"} {
		t.Run("invalid_"+value, func(t *testing.T) {
			t.Setenv("BEACON_HISTORY_RETENTION_DAYS", value)
			age, err := RetentionFromEnv()
			if err == nil || age != 0 {
				t.Fatal("invalid configuration accepted")
			}
		})
	}
}
