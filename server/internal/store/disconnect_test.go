package store

import (
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"beacon.local/server/internal/auth"
	"beacon.local/server/internal/domain"
)

func TestDisconnectGenerationBarrierAndCrashRecovery(t *testing.T) {
	path := filepath.Join(t.TempDir(), "disconnect.db")
	repository, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	device, err := repository.CreateDevice("one")
	if err != nil {
		t.Fatal(err)
	}
	other, err := repository.CreateDevice("other")
	if err != nil {
		t.Fatal(err)
	}
	deliveryMessage(t, repository, device.DeviceID)
	deliveryMessage(t, repository, other.DeviceID)
	_, err = repository.RotateDevice(device.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UnixMilli()
	claimed, err := repository.ClaimPublish(now, 10)
	if err != nil || len(claimed) != 2 {
		t.Fatal("credential-generation disconnect must not block new WebSocket publication")
	}
	page, err := repository.Messages(device.DeviceID, 0, 100, true)
	if err != nil || len(page.Messages) != 1 {
		t.Fatal("barrier blocked HTTP history")
	}
	task, err := repository.ClaimDisconnect(now)
	if err != nil || task == nil {
		t.Fatal("task missing")
	}
	expected := domain.ReceiverClientID(device.DeviceID, auth.HashToken(device.ReceiveToken))
	if task.ClientID != expected {
		t.Fatal("wrong generation queued")
	}
	if next, err := repository.ClaimDisconnect(now + 1); err != nil || next != nil {
		t.Fatal("lease acquired twice")
	}
	repository.Close()
	repository, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()
	if next, err := repository.ClaimDisconnect(now + 1); err != nil || next != nil {
		t.Fatal("restart lost lease")
	}
	reclaimed, err := repository.ClaimDisconnect(task.LeaseUntil)
	if err != nil || reclaimed == nil || reclaimed.LeaseToken == task.LeaseToken {
		t.Fatal("crash lease recovery failed")
	}
	if done, err := repository.CompleteDisconnect(*task, true, task.LeaseUntil); err != nil || done {
		t.Fatal("stale worker retired replacement")
	}
	if done, err := repository.CompleteDisconnect(*reclaimed, false, reclaimed.LeaseUntil-1); err != nil || !done {
		t.Fatal("failure persistence failed")
	}
	if next, err := repository.ClaimDisconnect(reclaimed.LeaseUntil); err != nil || next != nil {
		t.Fatal("failure retried immediately")
	}
	var until, next int64
	if err := repository.db.QueryRow("SELECT until_ms,next_attempt FROM session_disconnects WHERE client_id=?", expected).Scan(&until, &next); err != nil {
		t.Fatal(err)
	}
	retry, err := repository.ClaimDisconnect(next)
	if err != nil || retry == nil {
		t.Fatal(err)
	}
	if done, err := repository.CompleteDisconnect(*retry, true, next+1); err != nil || !done {
		t.Fatal("successful early kick failed")
	}
	stats, err := repository.StorageStats()
	if err != nil || stats.QueuedDisconnects != 1 {
		t.Fatal("early kick cleared race guard")
	}
	final, err := repository.ClaimDisconnect(until + domain.SessionDisconnectRepeat.Milliseconds())
	if err != nil || final == nil {
		t.Fatal(err)
	}
	if done, err := repository.CompleteDisconnect(*final, false, final.LeaseUntil-1); err != nil || !done {
		t.Fatal("final failure lost barrier")
	}
	if err := repository.db.QueryRow("SELECT next_attempt FROM session_disconnects WHERE client_id=?", expected).Scan(&next); err != nil {
		t.Fatal(err)
	}
	final, err = repository.ClaimDisconnect(next)
	if err != nil || final == nil {
		t.Fatal(err)
	}
	if done, err := repository.CompleteDisconnect(*final, true, next+1); err != nil || !done {
		t.Fatal("final confirmation failed")
	}
	stats, err = repository.StorageStats()
	if err != nil || stats.QueuedDisconnects != 0 || stats.Pending != 2 {
		t.Fatal("disconnect changed messages/receipts")
	}
}

func TestCredentialMutationAndDisconnectEnqueueAreAtomic(t *testing.T) {
	repository, device := deliveryStore(t)
	for _, operation := range []string{"rotate", "revoke"} {
		t.Run(operation, func(t *testing.T) {
			if _, err := repository.db.Exec(`CREATE TRIGGER reject_disconnect BEFORE INSERT ON session_disconnects BEGIN SELECT RAISE(ABORT,'test'); END`); err != nil {
				t.Fatal(err)
			}
			if operation == "rotate" {
				if _, err := repository.RotateDevice(device.DeviceID); err == nil {
					t.Fatal("rotation survived enqueue failure")
				}
			} else {
				if err := repository.RevokeDevice(device.DeviceID); err == nil {
					t.Fatal("revocation survived enqueue failure")
				}
			}
			if id, err := repository.AuthenticateToken(device.ReceiveToken, false); err != nil || id != device.DeviceID {
				t.Fatal("credential transaction partially committed")
			}
			if _, err := repository.db.Exec("DROP TRIGGER reject_disconnect"); err != nil {
				t.Fatal(err)
			}
		})
	}
	newCredentials, err := repository.RotateDevice(device.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := repository.RotateDevice(device.DeviceID); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := repository.db.QueryRow("SELECT count(*) FROM session_disconnects WHERE device=?", device.DeviceID).Scan(&count); err != nil || count != 2 {
		t.Fatal("rotation overwrote previous generation")
	}
	var exists int
	if err := repository.db.QueryRow("SELECT count(*) FROM session_disconnects WHERE client_id=?", domain.ReceiverClientID(device.DeviceID, auth.HashToken(newCredentials.ReceiveToken))).Scan(&exists); err != nil || exists != 1 {
		t.Fatal("second generation missing")
	}
}

func TestVersionFourUpgradePreservesCompletedPublication(t *testing.T) {
	path := filepath.Join(t.TempDir(), "v4.db")
	repository, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	device, err := repository.CreateDevice("existing")
	if err != nil {
		t.Fatal(err)
	}
	message := deliveryMessage(t, repository, device.DeviceID)
	for _, query := range []string{"DELETE FROM publish_outbox", "DROP TABLE session_disconnects", "PRAGMA user_version=4"} {
		if _, err := repository.db.Exec(query); err != nil {
			t.Fatal(err)
		}
	}
	identity := repository.ServerID()
	repository.Close()
	repository, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()
	stats, err := repository.StorageStats()
	if err != nil || stats.QueuedRealtime != 0 || stats.QueuedDisconnects != 0 || stats.Pending != 1 || repository.ServerID() != identity {
		t.Fatal("migration changed identity or resurrected completed delivery")
	}
	page, err := repository.Messages(device.DeviceID, 0, 100, true)
	if err != nil || len(page.Messages) != 1 || !reflect.DeepEqual(page.Messages[0], message) {
		t.Fatal("migration lost history")
	}
	if _, err := repository.RotateDevice(device.DeviceID); err != nil {
		t.Fatal("new queue unavailable after upgrade")
	}
	if _, err := repository.db.Exec("PRAGMA user_version=12"); err != nil {
		t.Fatal(err)
	}
	repository.Close()
	if future, err := Open(path); err == nil {
		future.Close()
		t.Fatal("future schema accepted")
	}
}
