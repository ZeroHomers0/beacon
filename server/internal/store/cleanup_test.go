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

func TestCleanupProtectsPendingAndKeepsRetryReceipts(t *testing.T) {
	anchor := time.Now().Add(-time.Hour).UnixMilli()
	path := filepath.Join(t.TempDir(), "cleanup.db")
	repository, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { repository.Close() }()
	first, err := repository.CreateDevice("first")
	if err != nil {
		t.Fatal(err)
	}
	second, err := repository.CreateDevice("second")
	if err != nil {
		t.Fatal(err)
	}
	messages := make([]domain.Message, 4)
	for i := range messages {
		messages[i], err = repository.SaveMessage(first.DeviceID, "title", "subtitle", "body")
		if err != nil {
			t.Fatal(err)
		}
		if _, err = repository.db.Exec("UPDATE messages SET created=? WHERE id=?", anchor+100+int64(i), messages[i].ID); err != nil {
			t.Fatal(err)
		}
	}
	if err = repository.Ack(first.DeviceID, []string{messages[0].ID, messages[2].ID, messages[3].ID}); err != nil {
		t.Fatal(err)
	}
	stats, err := repository.StorageStats()
	if err != nil || stats.Messages != 4 || stats.Pending != 1 || stats.Confirmed != 3 || stats.Devices != 2 || stats.DatabaseBytes <= 0 {
		t.Fatalf("unexpected statistics: %+v, %v", stats, err)
	}
	preview, err := repository.CleanupConfirmed(anchor+102, false)
	if err != nil || preview.Eligible != 1 || preview.ProtectedPending != 1 || preview.Deleted != 0 || preview.Applied {
		t.Fatalf("unexpected preview: %+v, %v", preview, err)
	}
	stats, err = repository.StorageStats()
	if err != nil || stats.Messages != 4 {
		t.Fatal("preview deleted history")
	}
	result, err := repository.CleanupConfirmed(anchor+102, true)
	if err != nil || result.Deleted != 1 || result.ProtectedPending != 1 || !result.Applied {
		t.Fatalf("unexpected cleanup: %+v, %v", result, err)
	}
	page, err := repository.Messages(first.DeviceID, 0, 10, false)
	if err != nil || len(page.Messages) != 3 || page.Messages[0].ID != messages[1].ID || page.NextCursor != messages[3].Sequence {
		t.Fatal("cleanup lost pending/boundary/newer message or changed sequence")
	}
	if err = repository.Ack(first.DeviceID, []string{messages[0].ID}); err != nil {
		t.Fatal("confirmation retry failed after cleanup")
	}
	if err = repository.Ack(second.DeviceID, []string{messages[0].ID}); !errors.Is(err, domain.ErrUnknownMessage) {
		t.Fatal("receipt crossed device boundary")
	}
	if err = repository.Ack(first.DeviceID, []string{messages[1].ID, strings.Repeat("a", domain.TokenLength)}); !errors.Is(err, domain.ErrUnknownMessage) {
		t.Fatal("unknown confirmation accepted")
	}
	pending, err := repository.Messages(first.DeviceID, 0, 10, true)
	if err != nil || len(pending.Messages) != 1 || pending.Messages[0].ID != messages[1].ID {
		t.Fatal("mixed invalid confirmation did not roll back")
	}
	if _, err = repository.CleanupConfirmed(anchor+102, true); err != nil {
		t.Fatal(err)
	}
	repository.Close()
	repository, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err = repository.Ack(first.DeviceID, []string{messages[0].ID}); err != nil {
		t.Fatal("receipt lost on reopen")
	}
	newMessage, err := repository.SaveMessage(first.DeviceID, "new", "", "body")
	if err != nil || newMessage.Sequence <= messages[3].Sequence {
		t.Fatal("cleanup reused sequence")
	}
}

func TestCleanupFailureRollsBackReceiptsAndMessages(t *testing.T) {
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
	if _, err = repository.db.Exec("CREATE TRIGGER block_delete BEFORE DELETE ON messages BEGIN SELECT RAISE(ABORT,'blocked'); END"); err != nil {
		t.Fatal(err)
	}
	if _, err = repository.CleanupConfirmed(message.CreatedAt+1, true); err == nil {
		t.Fatal("expected storage failure")
	}
	var receipts, messages int
	if err = repository.db.QueryRow("SELECT count(*) FROM confirmed_receipts").Scan(&receipts); err != nil {
		t.Fatal(err)
	}
	if err = repository.db.QueryRow("SELECT count(*) FROM messages").Scan(&messages); err != nil {
		t.Fatal(err)
	}
	if receipts != 0 || messages != 1 {
		t.Fatal("partial cleanup committed")
	}
}

func TestVersionTwoUpgradeKeepsHistoryAndIdentity(t *testing.T) {
	path := filepath.Join(t.TempDir(), "v2.db")
	repository, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	device, err := repository.CreateDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	message, err := repository.SaveMessage(device.DeviceID, "title", "subtitle", "body")
	if err != nil {
		t.Fatal(err)
	}
	for _, query := range []string{"DROP TABLE confirmed_receipts", "DROP INDEX messages_cleanup", "PRAGMA user_version=2"} {
		if _, err = repository.db.Exec(query); err != nil {
			t.Fatal(err)
		}
	}
	repository.Close()
	repository, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()
	if repository.ServerID() != device.ServerID {
		t.Fatal("upgrade changed server identity")
	}
	if id, err := repository.AuthenticateToken(device.ReceiveToken, false); err != nil || id != device.DeviceID {
		t.Fatal("upgrade changed credentials")
	}
	page, err := repository.Messages(device.DeviceID, 0, 10, false)
	if err != nil || len(page.Messages) != 1 || !reflect.DeepEqual(page.Messages[0], message) {
		t.Fatal("upgrade changed history")
	}
	var version int
	if err = repository.db.QueryRow("PRAGMA user_version").Scan(&version); err != nil || version != 11 {
		t.Fatal("upgrade did not install schema v9")
	}
}
