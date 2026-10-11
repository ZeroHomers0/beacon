package store

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"beacon.local/server/internal/domain"
)

func TestRetentionBatchProtectsPendingBoundaryAndReceiptRetry(t *testing.T) {
	anchor := time.Now().Add(-time.Hour).UnixMilli()
	repository, err := Open(filepath.Join(t.TempDir(), "retention.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()
	device, err := repository.CreateDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	other, err := repository.CreateDevice("other")
	if err != nil {
		t.Fatal(err)
	}
	var ids []string
	for i := 0; i < 6; i++ {
		message, err := repository.SaveMessage(device.DeviceID, "title", "subtitle", "body")
		if err != nil {
			t.Fatal(err)
		}
		ids = append(ids, message.ID)
		if _, err = repository.db.Exec("UPDATE messages SET created=? WHERE id=?", anchor+100+int64(i), message.ID); err != nil {
			t.Fatal(err)
		}
	}
	if err = repository.Ack(device.DeviceID, []string{ids[0], ids[2], ids[3], ids[4], ids[5]}); err != nil {
		t.Fatal(err)
	}
	deleted, err := repository.CleanupConfirmedBatch(context.Background(), anchor+105, 2)
	if err != nil || deleted != 2 {
		t.Fatalf("batch: %d %v", deleted, err)
	}
	stats, err := repository.StorageStats()
	if err != nil || stats.Messages != 4 || stats.Pending != 1 || stats.Receipts != 2 || stats.OldestPendingMS != anchor+101 || stats.OldestConfirmedMS != anchor+103 {
		t.Fatalf("stats: %+v %v", stats, err)
	}
	if err = repository.Ack(device.DeviceID, []string{ids[0], ids[2]}); err != nil {
		t.Fatal("receipt retry failed", err)
	}
	if err = repository.Ack(other.DeviceID, []string{ids[0]}); !errors.Is(err, domain.ErrUnknownMessage) {
		t.Fatal("receipt crossed device")
	}
	deleted, err = repository.CleanupConfirmedBatch(context.Background(), anchor+105, 100)
	if err != nil || deleted != 2 {
		t.Fatalf("second batch: %d %v", deleted, err)
	}
	page, err := repository.Messages(device.DeviceID, 0, 100, false)
	if err != nil || len(page.Messages) != 2 || page.Messages[0].ID != ids[1] || page.Messages[1].ID != ids[5] {
		t.Fatal("pending/boundary lost", err)
	}
	stats, err = repository.StorageStats()
	if err != nil || stats.QueuedRealtime != 1 {
		t.Fatal("pending outbox changed", err)
	}
	newMessage, err := repository.SaveMessage(device.DeviceID, "new", "", "body")
	if err != nil || newMessage.Sequence <= page.NextCursor {
		t.Fatal("sequence reused", err)
	}
}

func TestRetentionRollbackCancellationAndInvalidBounds(t *testing.T) {
	repository, err := Open(filepath.Join(t.TempDir(), "rollback.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()
	device, err := repository.CreateDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	message, err := repository.SaveMessage(device.DeviceID, "title", "", "body")
	if err != nil {
		t.Fatal(err)
	}
	if err = repository.Ack(device.DeviceID, []string{message.ID}); err != nil {
		t.Fatal(err)
	}
	cutoff := time.Now().Add(-time.Hour).UnixMilli()
	if _, err = repository.db.Exec("UPDATE messages SET created=?", cutoff-1); err != nil {
		t.Fatal(err)
	}
	for _, bounds := range []struct {
		before int64
		limit  int
	}{{0, 1}, {cutoff, 0}, {cutoff, 101}, {time.Now().Add(time.Hour).UnixMilli(), 1}} {
		if _, err = repository.CleanupConfirmedBatch(context.Background(), bounds.before, bounds.limit); !errors.Is(err, domain.ErrInvalidCutoff) {
			t.Fatal("invalid bound accepted")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err = repository.CleanupConfirmedBatch(ctx, cutoff, 1); !errors.Is(err, context.Canceled) {
		t.Fatal("cancel ignored", err)
	}
	if _, err = repository.db.Exec("CREATE TRIGGER block_delete BEFORE DELETE ON messages BEGIN SELECT RAISE(ABORT,'blocked'); END"); err != nil {
		t.Fatal(err)
	}
	if _, err = repository.CleanupConfirmedBatch(context.Background(), cutoff, 1); err == nil {
		t.Fatal("expected rollback")
	}
	stats, err := repository.StorageStats()
	if err != nil || stats.Messages != 1 || stats.Receipts != 0 {
		t.Fatal("partial commit", stats, err)
	}
}
