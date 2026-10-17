package store

import (
	"beacon.local/server/internal/domain"
	"context"
	"errors"
	"path/filepath"
	"reflect"
	"sync"
	"testing"
)

func TestConcurrentIdempotencyAndExpiry(t *testing.T) {
	a, err := Open(filepath.Join(t.TempDir(), "push.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	device, err := a.CreateDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	results := make(chan domain.Message, 12)
	failures := make(chan error, 12)
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			m, e := a.SavePushMessage(device.DeviceID, "", "", "hello", 5, "", device.PushKey, "", domain.PushOptions{Key: "same", Group: "NAS", TTL: 1})
			if e != nil {
				failures <- e
			} else {
				results <- m
			}
		}()
	}
	wg.Wait()
	close(results)
	close(failures)
	for e := range failures {
		t.Fatal(e)
	}
	var first domain.Message
	for m := range results {
		if first.ID == "" {
			first = m
		} else if !reflect.DeepEqual(first, m) {
			t.Fatal("duplicate insertion")
		}
	}
	if first.ExpiresAt-first.CreatedAt != 1000 {
		t.Fatal("ttl")
	}
	stats, _ := a.StorageStats()
	if stats.Messages != 1 || stats.QueuedRealtime != 1 {
		t.Fatal("outbox duplicated", stats)
	}
	if _, err = a.db.Exec("UPDATE messages SET expires_at=? WHERE id=?", first.CreatedAt-1, first.ID); err != nil {
		t.Fatal(err)
	}
	page, err := a.Messages(device.DeviceID, 0, 100, true)
	if err != nil || len(page.Messages) != 0 {
		t.Fatal("expired HTTP delivery", err)
	}
	tasks, err := a.ClaimPublish(first.CreatedAt, 100)
	if err != nil || len(tasks) != 0 {
		t.Fatal("expired websocket delivery", err)
	}
	n, err := a.ExpireDeliveryBatch(context.Background(), first.CreatedAt)
	if err != nil || n != 1 {
		t.Fatal(n, err)
	}
	if err = a.Ack(device.DeviceID, []string{first.ID}); err != nil {
		t.Fatal("expired receipt cannot retry", err)
	}
	replay, err := a.SavePushMessage(device.DeviceID, "", "", "hello", 5, "", device.PushKey, "", domain.PushOptions{Key: "same", Group: "NAS", TTL: 1})
	if err != nil || !reflect.DeepEqual(replay, first) {
		t.Fatal("expired replay created fresh alert", err)
	}
	_, err = a.SavePushMessage(device.DeviceID, "", "", "changed", 5, "", device.PushKey, "", domain.PushOptions{Key: "same", Group: "NAS", TTL: 1})
	if !errors.Is(err, domain.ErrIdempotencyConflict) {
		t.Fatal(err)
	}
}
