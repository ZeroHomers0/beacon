package store

import (
	"path/filepath"
	"testing"
)

func TestLegacySessionQueueMigrationPreservesIdentityAndHistory(t *testing.T) {
	path := filepath.Join(t.TempDir(), "legacy.db")
	repository, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	device, err := repository.CreateDevice("migration")
	if err != nil {
		t.Fatal(err)
	}
	deliveryMessage(t, repository, device.DeviceID)
	if _, err = repository.RotateDevice(device.DeviceID); err != nil {
		t.Fatal(err)
	}
	identity := repository.ServerID()
	if _, err = repository.db.Exec("ALTER TABLE session_disconnects RENAME TO broker_disconnects; PRAGMA user_version=7"); err != nil {
		t.Fatal(err)
	}
	repository.Close()
	repository, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()
	if repository.ServerID() != identity {
		t.Fatal("identity changed")
	}
	var version, count, legacy int
	if err = repository.db.QueryRow("PRAGMA user_version").Scan(&version); err != nil || version != messageSchemaVersion {
		t.Fatal(version, err)
	}
	if err = repository.db.QueryRow("SELECT count(*) FROM session_disconnects").Scan(&count); err != nil || count != 1 {
		t.Fatal(count, err)
	}
	if err = repository.db.QueryRow("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='broker_disconnects'").Scan(&legacy); err != nil || legacy != 0 {
		t.Fatal(legacy, err)
	}
	page, err := repository.Messages(device.DeviceID, 0, 100, true)
	if err != nil || len(page.Messages) != 1 {
		t.Fatal("history lost", err)
	}
}
