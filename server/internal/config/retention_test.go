package config

import (
	"testing"
	"time"
)

func TestRetentionConfiguration(t *testing.T) {
	for _, value := range []string{"", "0", "1", "30", "3650"} {
		t.Run("valid_"+value, func(t *testing.T) {
			t.Setenv("BEACON_HISTORY_RETENTION_DAYS", value)
			age, err := RetentionFromEnv()
			if err != nil || age < 0 || (value == "30" && age != 30*24*time.Hour) {
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
