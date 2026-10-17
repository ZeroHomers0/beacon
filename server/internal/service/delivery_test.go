package service_test

import (
	"context"
	"errors"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"beacon.local/server/internal/domain"
	"beacon.local/server/internal/service"
	"beacon.local/server/internal/store"
)

type testPublisher func(context.Context, string, string, domain.Message) error

func (p testPublisher) Publish(ctx context.Context, serverID, deviceID string, message domain.Message) error {
	return p(ctx, serverID, deviceID, message)
}

func TestDeliveryFailureDoesNotUndoAcceptanceOrBlockOtherDevices(t *testing.T) {
	repository, err := store.Open(filepath.Join(t.TempDir(), "worker.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()
	app := service.New(repository)
	first, err := app.CreateDevice("first")
	if err != nil {
		t.Fatal(err)
	}
	second, err := app.CreateDevice("second")
	if err != nil {
		t.Fatal(err)
	}
	m1, err := app.Push(first.DeviceID, "title", "subtitle", "body")
	if err != nil {
		t.Fatal(err)
	}
	m2, err := app.Push(second.DeviceID, "title", "subtitle", "body")
	if err != nil {
		t.Fatal(err)
	}
	calls := 0
	publisher := testPublisher(func(ctx context.Context, serverID, deviceID string, m domain.Message) error {
		calls++
		if serverID != repository.ServerID() {
			t.Fatal("wrong identity")
		}
		if _, ok := ctx.Deadline(); !ok {
			t.Fatal("unbounded publish")
		}
		if deviceID == first.DeviceID {
			if !reflect.DeepEqual(m, m1) {
				t.Fatal("message mismatch")
			}
			return errors.New("broker offline")
		}
		if deviceID != second.DeviceID || !reflect.DeepEqual(m, m2) {
			t.Fatal("device mismatch")
		}
		return nil
	})
	result, err := service.DrainDelivery(context.Background(), repository, publisher, 10)
	if err != nil || result.Published != 1 || result.Retrying != 1 || calls != 2 {
		t.Fatalf("wrong result: %+v %v", result, err)
	}
	stats, err := repository.StorageStats()
	if err != nil || stats.Messages != 2 || stats.Pending != 2 || stats.QueuedRealtime != 1 {
		t.Fatalf("broker result modified receipt/history: %+v %v", stats, err)
	}
	result, err = service.DrainDelivery(context.Background(), repository, publisher, 10)
	if err != nil || result.Claimed != 0 || calls != 2 {
		t.Fatal("immediate retry loop")
	}
}

func TestShutdownPreservesUnprocessedLeases(t *testing.T) {
	repository, err := store.Open(filepath.Join(t.TempDir(), "shutdown.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()
	device, err := repository.CreateDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if _, err := repository.SaveMessage(device.DeviceID, "title", "", "body"); err != nil {
			t.Fatal(err)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	calls := 0
	publisher := testPublisher(func(ctx context.Context, _, _ string, _ domain.Message) error { calls++; cancel(); return ctx.Err() })
	result, err := service.DrainDelivery(ctx, repository, publisher, 10)
	if !errors.Is(err, context.Canceled) || result.Claimed != 2 || result.Retrying != 1 || calls != 1 {
		t.Fatalf("bad shutdown: %+v %v", result, err)
	}
	stats, err := repository.StorageStats()
	if err != nil || stats.QueuedRealtime != 2 {
		t.Fatal("shutdown dropped work")
	}
	tasks, err := repository.ClaimPublish(time.Now().Add(time.Minute).UnixMilli(), 10)
	if err != nil || len(tasks) != 2 {
		t.Fatal("shutdown work cannot be recovered")
	}
}

func TestWorkerRechecksRevocationBeforeNextPublish(t *testing.T) {
	repository, err := store.Open(filepath.Join(t.TempDir(), "revoke.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()
	first, err := repository.CreateDevice("first")
	if err != nil {
		t.Fatal(err)
	}
	second, err := repository.CreateDevice("second")
	if err != nil {
		t.Fatal(err)
	}
	for _, device := range []string{first.DeviceID, second.DeviceID} {
		if _, err := repository.SaveMessage(device, "title", "", "body"); err != nil {
			t.Fatal(err)
		}
	}
	calls := 0
	publisher := testPublisher(func(_ context.Context, _, deviceID string, _ domain.Message) error {
		calls++
		if deviceID != first.DeviceID {
			t.Fatal("revoked device reached publisher")
		}
		return repository.RevokeDevice(second.DeviceID)
	})
	result, err := service.DrainDelivery(context.Background(), repository, publisher, 10)
	if err != nil || result.Published != 1 || result.Skipped != 1 || calls != 1 {
		t.Fatalf("revocation not rechecked: %+v %v", result, err)
	}
}
