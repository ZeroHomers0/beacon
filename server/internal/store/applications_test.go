package store

import (
	"errors"
	"path/filepath"
	"testing"

	"beacon.local/server/internal/domain"
)

func TestApplicationCredentialsScopePriorityAndRevocation(t *testing.T) {
	a, err := Open(filepath.Join(t.TempDir(), "apps.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	device, err := a.CreateDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	other, err := a.CreateDevice("other")
	if err != nil {
		t.Fatal(err)
	}
	credential, err := a.CreateApplication(device.DeviceID, "backup", 8)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := a.AuthenticateToken(credential.Token, false); err == nil {
		t.Fatal("application token accepted for receiving")
	}
	message, err := a.SavePriorityMessage(device.DeviceID, "backup", "", "done", 8, credential.ID, credential.Token)
	if err != nil || message.Priority != 8 || message.ApplicationID != credential.ID {
		t.Fatal("metadata not saved", err)
	}
	page, err := a.Messages(device.DeviceID, 0, 10, true)
	if err != nil || len(page.Messages) != 1 || page.Messages[0].Priority != 8 {
		t.Fatal("metadata lost")
	}
	if _, err := a.SavePriorityMessage(other.DeviceID, "", "", "body", 8, credential.ID, credential.Token); err == nil {
		t.Fatal("cross-device sending allowed")
	}
	if err := a.RevokeApplication(credential.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := a.ApplicationForToken(credential.Token); err == nil {
		t.Fatal("revoked token authenticated")
	}
	if _, err := a.SavePriorityMessage(device.DeviceID, "", "", "body", 8, credential.ID, credential.Token); err == nil {
		t.Fatal("revocation race not checked")
	}
	if _, err := a.SaveMessage(device.DeviceID, "legacy", "", "body"); err != nil {
		t.Fatal("legacy device key path broken")
	}
	if _, err := a.SavePriorityMessage(device.DeviceID, "", "", "body", 11, "", ""); err != domain.ErrInvalidMessage {
		t.Fatal("invalid priority accepted")
	}
}

func TestApplicationDeletionRemovesOnlyItsOwnMessagesAndReceipts(t *testing.T) {
	a, err := Open(filepath.Join(t.TempDir(), "delete-app.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	device, err := a.CreateDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	other, err := a.CreateDevice("other")
	if err != nil {
		t.Fatal(err)
	}
	first, err := a.CreateApplication(device.DeviceID, "NAS", 8)
	if err != nil {
		t.Fatal(err)
	}
	sibling, err := a.CreateApplication(device.DeviceID, "CI", 5)
	if err != nil {
		t.Fatal(err)
	}
	foreign, err := a.CreateApplication(other.DeviceID, "other-app", 5)
	if err != nil {
		t.Fatal(err)
	}
	sent, err := a.SavePriorityMessage(device.DeviceID, "one", "", "body", 8, first.ID, first.Token)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = a.SavePriorityMessage(device.DeviceID, "two", "", "body", 5, sibling.ID, sibling.Token); err != nil {
		t.Fatal(err)
	}
	foreignMessage, err := a.SavePriorityMessage(other.DeviceID, "three", "", "body", 5, foreign.ID, foreign.Token)
	if err != nil {
		t.Fatal(err)
	}
	// A device-key push carries no application and must survive both deletions.
	if _, err = a.SaveMessage(device.DeviceID, "manual", "", "body"); err != nil {
		t.Fatal(err)
	}
	var queued int
	if err = a.db.QueryRow("SELECT count(*) FROM publish_outbox o JOIN messages m ON m.seq=o.message_seq WHERE m.application_id=?", first.ID).Scan(&queued); err != nil || queued != 1 {
		t.Fatalf("outbox not queued: %d %v", queued, err)
	}
	// An active application is not removable, matching device deletion.
	if _, err = a.DeleteApplication(first.ID); !errors.Is(err, domain.ErrInvalidDevice) {
		t.Fatal("active application deleted", err)
	}
	if err = a.RevokeApplication(first.ID); err != nil {
		t.Fatal(err)
	}
	deleted, err := a.DeleteApplication(first.ID)
	if err != nil || deleted != 1 {
		t.Fatalf("delete: %d %v", deleted, err)
	}
	if _, err = a.DeleteApplication(first.ID); !errors.Is(err, domain.ErrInvalidDevice) {
		t.Fatal("repeated delete accepted", err)
	}
	var stored int
	if err = a.db.QueryRow("SELECT count(*) FROM messages WHERE application_id=?", first.ID).Scan(&stored); err != nil || stored != 0 {
		t.Fatalf("application messages survived: %d %v", stored, err)
	}
	// Its queued realtime publication went with the message; the other three keep theirs.
	if err = a.db.QueryRow("SELECT count(*) FROM publish_outbox").Scan(&queued); err != nil || queued != 3 {
		t.Fatalf("outbox not trimmed to surviving messages: %d %v", queued, err)
	}
	if err = a.db.QueryRow("SELECT count(*) FROM publish_outbox o JOIN messages m ON m.seq=o.message_seq WHERE m.application_id=?", first.ID).Scan(&queued); err != nil || queued != 0 {
		t.Fatalf("deleted application still queued: %d %v", queued, err)
	}
	// The sibling application's message and the device-key message are untouched.
	page, err := a.Messages(device.DeviceID, 0, 10, false)
	if err != nil || len(page.Messages) != 2 {
		t.Fatalf("sibling message lost: %+v %v", page.Messages, err)
	}
	for _, message := range page.Messages {
		if message.ID == sent.ID || message.ApplicationID == first.ID {
			t.Fatal("deleted application message still listed")
		}
	}
	foreignPage, err := a.Messages(other.DeviceID, 0, 10, false)
	if err != nil || len(foreignPage.Messages) != 1 || foreignPage.Messages[0].ID != foreignMessage.ID {
		t.Fatalf("foreign application message lost: %+v %v", foreignPage.Messages, err)
	}
	if _, err = a.ApplicationForToken(first.Token); err == nil {
		t.Fatal("deleted application authenticated")
	}
	if _, err = a.ApplicationForToken(sibling.Token); err != nil {
		t.Fatal("sibling application rejected", err)
	}
	// The application row is gone, so nothing is left to list as revoked.
	applications, err := a.ListApplications()
	if err != nil || len(applications) != 2 {
		t.Fatalf("applications not removed: %+v %v", applications, err)
	}
}

// Retention retires an old confirmed message into a minimal receipt that no longer names its
// application, so deleting the application afterwards can only remove what is still stored.
func TestApplicationDeletionLeavesRetiredReceiptsToAgeOut(t *testing.T) {
	a, err := Open(filepath.Join(t.TempDir(), "retired-receipt.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	device, err := a.CreateDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	application, err := a.CreateApplication(device.DeviceID, "NAS", 5)
	if err != nil {
		t.Fatal(err)
	}
	message, err := a.SavePriorityMessage(device.DeviceID, "old", "", "body", 5, application.ID, application.Token)
	if err != nil {
		t.Fatal(err)
	}
	if err = a.Ack(device.DeviceID, []string{message.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err = a.CleanupConfirmed(message.CreatedAt+1, true); err != nil {
		t.Fatal(err)
	}
	var receipts int
	if err = a.db.QueryRow("SELECT count(*) FROM confirmed_receipts WHERE device=? AND id=?", device.DeviceID, message.ID).Scan(&receipts); err != nil || receipts != 1 {
		t.Fatalf("receipt not recorded: %d %v", receipts, err)
	}
	if err = a.RevokeApplication(application.ID); err != nil {
		t.Fatal(err)
	}
	if _, err = a.DeleteApplication(application.ID); err != nil {
		t.Fatal(err)
	}
	// The body is already gone; the receipt keeps its retry window and is untouched.
	if err = a.db.QueryRow("SELECT count(*) FROM confirmed_receipts WHERE device=? AND id=?", device.DeviceID, message.ID).Scan(&receipts); err != nil || receipts != 1 {
		t.Fatalf("retired receipt removed: %d %v", receipts, err)
	}
}

func TestVersionFiveUpgradePreservesDeviceHistoryAndOutbox(t *testing.T) {
	path := filepath.Join(t.TempDir(), "old.db")
	a, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	device, err := a.CreateDevice("existing phone")
	if err != nil {
		t.Fatal(err)
	}
	message, err := a.SaveMessage(device.DeviceID, "old", "subtitle", "body")
	if err != nil {
		t.Fatal(err)
	}
	// Reconstruct the actual schema 5 shape, keeping real credentials and outbox rows.
	for _, query := range []string{
		"DROP TABLE applications",
		"ALTER TABLE messages DROP COLUMN priority",
		"ALTER TABLE messages DROP COLUMN application_id",
		"PRAGMA user_version=5",
	} {
		if _, err := a.db.Exec(query); err != nil {
			t.Fatal(err)
		}
	}
	a.Close()
	a, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	if a.ServerID() != device.ServerID {
		t.Fatal("server identity changed")
	}
	if id, err := a.AuthenticateToken(device.ReceiveToken, false); err != nil || id != device.DeviceID {
		t.Fatal("receive credential changed")
	}
	page, err := a.Messages(device.DeviceID, 0, 10, true)
	if err != nil || len(page.Messages) != 1 || page.Messages[0].ID != message.ID || page.Messages[0].Priority != 5 || page.Messages[0].Subtitle != "subtitle" {
		t.Fatal("history or default priority lost")
	}
	var queued int
	if err := a.db.QueryRow("SELECT count(*) FROM publish_outbox").Scan(&queued); err != nil || queued != 1 {
		t.Fatal("pending delivery lost")
	}
	if _, err := a.CreateApplication(device.DeviceID, "new sender", 8); err != nil {
		t.Fatal("new applications unavailable", err)
	}
}
