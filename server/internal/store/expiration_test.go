package store

import (
	"beacon.local/server/internal/domain"
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"
)

func TestExpirationStopsDeliveryRetainsScopedReceiptsAndBoundsReclamation(t *testing.T) {
	a, err := Open(filepath.Join(t.TempDir(), "expiration.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	device, _ := a.CreateDevice("expiry")
	other, _ := a.CreateDevice("other")
	message, err := a.SaveMessage(device.DeviceID, "", "", "expired body")
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UnixMilli()
	created := now - domain.PendingLifetime.Milliseconds() - 1000
	if _, err = a.db.Exec("UPDATE messages SET created=? WHERE id=?", created, message.ID); err != nil {
		t.Fatal(err)
	}
	page, err := a.Messages(device.DeviceID, 0, 100, false)
	if err != nil || len(page.Messages) != 0 {
		t.Fatal("expired HTTP redelivery", err)
	}
	tasks, err := a.ClaimPublish(now, 100)
	if err != nil || len(tasks) != 0 {
		t.Fatal("expired stream redelivery", err)
	}
	count, err := a.ExpireDeliveryBatch(context.Background(), now)
	if err != nil || count != 1 {
		t.Fatal("expiry did not delete body", count, err)
	}
	expired, err := a.ExpiredMessages(device.DeviceID)
	if err != nil || expired != 1 {
		t.Fatal("expiry count", err)
	}
	if err = a.Ack(device.DeviceID, []string{message.ID}); err != nil {
		t.Fatal("saved message receipt cannot retry", err)
	}
	if err = a.Ack(other.DeviceID, []string{message.ID}); !errors.Is(err, domain.ErrUnknownMessage) {
		t.Fatal("receipt crossed identity", err)
	}
	stats, err := a.StorageStats()
	if err != nil || stats.Messages != 0 || stats.QueuedRealtime != 0 {
		t.Fatal("body/outbox not atomic", err)
	}
	future := created + (domain.PendingLifetime + domain.ReceiptLifetime + domain.ReceiptGrace).Milliseconds() + 1
	if _, err = a.ExpireDeliveryBatch(context.Background(), future); err != nil {
		t.Fatal(err)
	}
	if err = a.Ack(device.DeviceID, []string{message.ID}); !errors.Is(err, domain.ErrUnknownMessage) {
		t.Fatal("receipt expiry not enforced", err)
	}
	expired, _ = a.ExpiredMessages(device.DeviceID)
	if expired != 1 {
		t.Fatal("repeated expiry changed total")
	}
}
