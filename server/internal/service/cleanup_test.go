package service_test

import (
	"errors"
	"path/filepath"
	"testing"
	"time"

	"beacon.local/server/internal/domain"
	"beacon.local/server/internal/service"
	"beacon.local/server/internal/store"
)

func TestCleanupRejectsInvalidCutoffWithoutDeleting(t *testing.T) {
	repository, err := store.Open(filepath.Join(t.TempDir(), "service.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()
	app := service.New(repository)
	for _, cutoff := range []int64{-1, 0, time.Now().Add(time.Hour).UnixMilli()} {
		if _, err = app.CleanupConfirmed(cutoff, true); !errors.Is(err, domain.ErrInvalidCutoff) {
			t.Fatalf("invalid cutoff accepted: %d", cutoff)
		}
	}
}
