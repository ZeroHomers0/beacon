package store

import (
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
