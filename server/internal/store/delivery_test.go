package store

import (
	"errors"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"beacon.local/server/internal/domain"
)

func deliveryStore(t *testing.T) (*Store, domain.DeviceCredentials) {
	t.Helper()
	repository, err := Open(filepath.Join(t.TempDir(), "delivery.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { repository.Close() })
	device, err := repository.CreateDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	return repository, device
}

func deliveryMessage(t *testing.T, repository *Store, device string) domain.Message {
	t.Helper()
	message, err := repository.SaveMessage(device, "title", "subtitle", "body")
	if err != nil {
		t.Fatal(err)
	}
	return message
}

func TestMessageAndDeliveryAreAtomic(t *testing.T) {
	repository, device := deliveryStore(t)
	if _, err := repository.db.Exec(`CREATE TRIGGER block_delivery BEFORE INSERT ON publish_outbox
		BEGIN SELECT RAISE(ABORT,'blocked'); END`); err != nil {
		t.Fatal(err)
	}
	if _, err := repository.SaveMessage(device.DeviceID, "title", "", "body"); err == nil {
		t.Fatal("accepted a message without its delivery intent")
	}
	stats, err := repository.StorageStats()
	if err != nil || stats.Messages != 0 || stats.QueuedRealtime != 0 {
		t.Fatalf("partial write: %+v %v", stats, err)
	}
	if _, err := repository.db.Exec("DROP TRIGGER block_delivery"); err != nil {
		t.Fatal(err)
	}
	message := deliveryMessage(t, repository, device.DeviceID)
	now := time.Now().UnixMilli()
	tasks, err := repository.ClaimPublish(now, 1)
	if err != nil || len(tasks) != 1 {
		t.Fatalf("missing delivery: %v %v", tasks, err)
	}
	if !reflect.DeepEqual(tasks[0].Message, message) || tasks[0].DeviceID != device.DeviceID || tasks[0].ServerID != repository.ServerID() {
		t.Fatal("HTTP and realtime did not share identity and content")
	}
	if tasks[0].Attempts != 1 || tasks[0].LeaseUntil != now+domain.PublishLease.Milliseconds() {
		t.Fatal("incorrect lease")
	}
}

func TestLeaseCrashRecoveryRejectsStaleCompletion(t *testing.T) {
	repository, device := deliveryStore(t)
	message := deliveryMessage(t, repository, device.DeviceID)
	now := time.Now().UnixMilli()
	first, err := repository.ClaimPublish(now, 1)
	if err != nil {
		t.Fatal(err)
	}
	if tasks, err := repository.ClaimPublish(now+1, 1); err != nil || len(tasks) != 0 {
		t.Fatal("duplicate active lease")
	}
	reclaimed, err := repository.ClaimPublish(now+domain.PublishLease.Milliseconds(), 1)
	if err != nil || len(reclaimed) != 1 || reclaimed[0].Attempts != 2 || reclaimed[0].LeaseToken == first[0].LeaseToken {
		t.Fatalf("crash not recovered: %+v %v", reclaimed, err)
	}
	if done, err := repository.CompletePublish(first[0], true, now+domain.PublishLease.Milliseconds()+1); err != nil || done {
		t.Fatal("stale worker retired new lease")
	}
	if done, err := repository.CompletePublish(reclaimed[0], true, now+domain.PublishLease.Milliseconds()+1); err != nil || !done {
		t.Fatal("new lease completion failed")
	}
	page, err := repository.Messages(device.DeviceID, 0, 10, true)
	if err != nil || len(page.Messages) != 1 || page.Messages[0].ID != message.ID {
		t.Fatal("broker completion was treated as client receipt")
	}
	if tasks, err := repository.ClaimPublish(now+2*domain.PublishLease.Milliseconds(), 1); err != nil || len(tasks) != 0 {
		t.Fatal("completed publication recreated")
	}
}

func TestRetryPersistsAcrossRestartWithoutTightLoop(t *testing.T) {
	path := filepath.Join(t.TempDir(), "restart.db")
	repository, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { repository.Close() }()
	device, err := repository.CreateDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	deliveryMessage(t, repository, device.DeviceID)
	now := time.Now().UnixMilli()
	tasks, err := repository.ClaimPublish(now, 1)
	if err != nil {
		t.Fatal(err)
	}
	if done, err := repository.CompletePublish(tasks[0], false, now+1); err != nil || !done {
		t.Fatal("failure not scheduled")
	}
	repository.Close()
	repository, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if tasks, err := repository.ClaimPublish(now+4999, 1); err != nil || len(tasks) != 0 {
		t.Fatal("early retry after restart")
	}
	tasks, err = repository.ClaimPublish(now+5001, 1)
	if err != nil || len(tasks) != 1 || tasks[0].Attempts != 2 {
		t.Fatal("durable retry missing")
	}
	// The caller cannot change persisted backoff by altering its task copy.
	tasks[0].Attempts = 30
	if done, err := repository.CompletePublish(tasks[0], false, now+5002); err != nil || !done {
		t.Fatal(err)
	}
	var next int64
	if err := repository.db.QueryRow("SELECT next_attempt FROM publish_outbox").Scan(&next); err != nil || next != now+15002 {
		t.Fatalf("wrong second delay: %d %v", next, err)
	}
}

func TestReceiptAndRevocationCancelOnlyOwnedTasks(t *testing.T) {
	repository, first := deliveryStore(t)
	second, err := repository.CreateDevice("second")
	if err != nil {
		t.Fatal(err)
	}
	m1 := deliveryMessage(t, repository, first.DeviceID)
	m2 := deliveryMessage(t, repository, second.DeviceID)
	now := time.Now().UnixMilli()
	tasks, err := repository.ClaimPublish(now, 10)
	if err != nil || len(tasks) != 2 {
		t.Fatal(err)
	}
	if err := repository.Ack(second.DeviceID, []string{m1.ID}); !errors.Is(err, domain.ErrUnknownMessage) {
		t.Fatal("cross-device receipt accepted")
	}
	if err := repository.Ack(first.DeviceID, []string{m1.ID, strings.Repeat("a", 64)}); !errors.Is(err, domain.ErrUnknownMessage) {
		t.Fatal("unknown receipt accepted")
	}
	if allowed, err := repository.Publishable(tasks[0], now+1); err != nil || !allowed {
		t.Fatal("invalid receipt partially removed task")
	}
	if err := repository.Ack(first.DeviceID, []string{m1.ID}); err != nil {
		t.Fatal(err)
	}
	if allowed, err := repository.Publishable(tasks[0], now+1); err != nil || allowed {
		t.Fatal("received task still publishable")
	}
	if err := repository.RevokeDevice(second.DeviceID); err != nil {
		t.Fatal(err)
	}
	if allowed, err := repository.Publishable(tasks[1], now+1); err != nil || allowed {
		t.Fatal("revoked task still publishable")
	}
	if _, err := repository.SaveMessage(second.DeviceID, "race", "", "body"); !errors.Is(err, domain.ErrInvalidDevice) {
		t.Fatal("revocation race accepted a message")
	}
	page, err := repository.Messages(second.DeviceID, 0, 10, true)
	if err != nil || len(page.Messages) != 1 || page.Messages[0].ID != m2.ID {
		t.Fatal("revocation erased history")
	}
	stats, err := repository.StorageStats()
	if err != nil || stats.QueuedRealtime != 0 || stats.Messages != 2 {
		t.Fatalf("incorrect queue cleanup: %+v %v", stats, err)
	}
}

func TestReceiptAndRevocationQueueFailureRollBack(t *testing.T) {
	repository, device := deliveryStore(t)
	message := deliveryMessage(t, repository, device.DeviceID)
	if _, err := repository.db.Exec(`CREATE TRIGGER block_cancel BEFORE DELETE ON publish_outbox
		BEGIN SELECT RAISE(ABORT,'blocked'); END`); err != nil {
		t.Fatal(err)
	}
	if err := repository.Ack(device.DeviceID, []string{message.ID}); err == nil {
		t.Fatal("partial receipt succeeded")
	}
	if err := repository.RevokeDevice(device.DeviceID); err == nil {
		t.Fatal("partial revocation succeeded")
	}
	if _, err := repository.AuthenticateToken(device.ReceiveToken, false); err != nil {
		t.Fatal("revocation committed without queue cancellation")
	}
	page, err := repository.Messages(device.DeviceID, 0, 10, true)
	if err != nil || len(page.Messages) != 1 {
		t.Fatal("receipt committed without queue cancellation")
	}
}

func TestVersionThreeBackfillsOnlyUnreceivedActiveHistoryOnce(t *testing.T) {
	path := filepath.Join(t.TempDir(), "v3.db")
	repository, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { repository.Close() }()
	device, err := repository.CreateDevice("active")
	if err != nil {
		t.Fatal(err)
	}
	revoked, err := repository.CreateDevice("revoked")
	if err != nil {
		t.Fatal(err)
	}
	pending := deliveryMessage(t, repository, device.DeviceID)
	confirmed := deliveryMessage(t, repository, device.DeviceID)
	deliveryMessage(t, repository, revoked.DeviceID)
	if err := repository.Ack(device.DeviceID, []string{confirmed.ID}); err != nil {
		t.Fatal(err)
	}
	if err := repository.RevokeDevice(revoked.DeviceID); err != nil {
		t.Fatal(err)
	}
	serverID := repository.ServerID()
	for _, q := range []string{"DROP TABLE publish_outbox", "PRAGMA user_version=3"} {
		if _, err := repository.db.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
	repository.Close()
	repository, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if repository.ServerID() != serverID {
		t.Fatal("identity changed")
	}
	if _, err := repository.AuthenticateToken(device.ReceiveToken, false); err != nil {
		t.Fatal("credential changed")
	}
	now := time.Now().UnixMilli()
	tasks, err := repository.ClaimPublish(now, 10)
	if err != nil || len(tasks) != 1 || !reflect.DeepEqual(tasks[0].Message, pending) {
		t.Fatalf("bad migration: %+v %v", tasks, err)
	}
	if done, err := repository.CompletePublish(tasks[0], true, now+1); err != nil || !done {
		t.Fatal(err)
	}
	repository.Close()
	repository, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if tasks, err := repository.ClaimPublish(now+60000, 10); err != nil || len(tasks) != 0 {
		t.Fatal("reopening backfilled an already-published message")
	}
}

func TestDeliveryBoundsAndExpiredCompletion(t *testing.T) {
	repository, device := deliveryStore(t)
	for _, n := range []int{0, -1, 101} {
		if _, err := repository.ClaimPublish(1, n); !errors.Is(err, domain.ErrInvalidDelivery) {
			t.Fatal("invalid batch accepted")
		}
	}
	deliveryMessage(t, repository, device.DeviceID)
	tasks, err := repository.ClaimPublish(1, 1)
	if err != nil {
		t.Fatal(err)
	}
	if done, err := repository.CompletePublish(tasks[0], true, tasks[0].LeaseUntil); err != nil || done {
		t.Fatal("expired completion accepted")
	}
}

func TestLeasedTaskSurvivesRotationAndHistoryCleanup(t *testing.T) {
	repository, device := deliveryStore(t)
	message := deliveryMessage(t, repository, device.DeviceID)
	now := time.Now().UnixMilli()
	tasks, err := repository.ClaimPublish(now, 1)
	if err != nil {
		t.Fatal(err)
	}
	rotated, err := repository.RotateDevice(device.DeviceID)
	if err != nil || rotated.DeviceID != device.DeviceID {
		t.Fatal(err)
	}
	if allowed, err := repository.Publishable(tasks[0], now+1); err != nil || !allowed {
		t.Fatal("rotation blocked delivery to the new authorized session")
	}
	if _, err := repository.AuthenticateToken(device.ReceiveToken, false); err == nil {
		t.Fatal("rotation kept old credential")
	}
	if err := repository.Ack(device.DeviceID, []string{message.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err := repository.CleanupConfirmed(message.CreatedAt+1, true); err != nil {
		t.Fatal(err)
	}
	if done, err := repository.CompletePublish(tasks[0], true, now+1); err != nil || done {
		t.Fatal("late completion resurrected cleared task")
	}
	stats, err := repository.StorageStats()
	if err != nil || stats.Messages != 0 || stats.QueuedRealtime != 0 || stats.Receipts != 1 {
		t.Fatalf("cleanup broke delivery/receipt: %+v %v", stats, err)
	}
}

func TestClaimFailureDoesNotPartiallyLeaseBatch(t *testing.T) {
	repository, device := deliveryStore(t)
	first := deliveryMessage(t, repository, device.DeviceID)
	second := deliveryMessage(t, repository, device.DeviceID)
	// Match the last row by its title, avoiding SQL interpolation of test values.
	if _, err := repository.db.Exec("UPDATE messages SET title='blocked' WHERE seq=?", second.Sequence); err != nil {
		t.Fatal(err)
	}
	if _, err := repository.db.Exec(`CREATE TRIGGER block_claim BEFORE UPDATE ON publish_outbox
		WHEN (SELECT title FROM messages WHERE seq=NEW.message_seq)='blocked'
		BEGIN SELECT RAISE(ABORT,'blocked'); END`); err != nil {
		t.Fatal(err)
	}
	if _, err := repository.ClaimPublish(time.Now().UnixMilli(), 10); err == nil {
		t.Fatal("partial batch lease succeeded")
	}
	var attempts int
	var token string
	if err := repository.db.QueryRow("SELECT attempts,lease_token FROM publish_outbox WHERE message_seq=?", first.Sequence).Scan(&attempts, &token); err != nil || attempts != 0 || token != "" {
		t.Fatal("first task leased despite rollback")
	}
}
