package store

import (
	"beacon.local/server/internal/domain"
	"errors"
	"path/filepath"
	"testing"
)

func TestAdminClearAndDeviceDeletion(t *testing.T) {
	s, err := Open(filepath.Join(t.TempDir(), "admin.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	first, err := s.CreateDevice("first")
	if err != nil {
		t.Fatal(err)
	}
	second, err := s.CreateDevice("second")
	if err != nil {
		t.Fatal(err)
	}
	a, err := s.SaveMessage(first.DeviceID, "title", "", "body")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.SaveMessage(second.DeviceID, "title", "", "body"); err != nil {
		t.Fatal(err)
	}
	if _, err = s.ClearMessages(first.DeviceID, true); !errors.Is(err, domain.ErrInvalidDevice) {
		t.Fatal("active device deleted", err)
	}
	if err = s.Ack(first.DeviceID, []string{a.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err = s.CleanupConfirmed(a.CreatedAt+1, true); err != nil {
		t.Fatal(err)
	}
	if err = s.RevokeDevice(first.DeviceID); err != nil {
		t.Fatal(err)
	}
	if _, err = s.ClearMessages(first.DeviceID, true); err != nil {
		t.Fatal(err)
	}
	stats, err := s.StorageStats()
	if err != nil || stats.Messages != 1 || stats.Devices != 1 || stats.Receipts != 0 {
		t.Fatalf("device cleanup: %+v %v", stats, err)
	}
	if n, err := s.ClearMessages("", false); err != nil || n != 1 {
		t.Fatal(n, err)
	}
	stats, err = s.StorageStats()
	if err != nil || stats.Messages != 0 || stats.Devices != 1 || stats.QueuedRealtime != 0 {
		t.Fatalf("clear all: %+v %v", stats, err)
	}
	next, err := s.SaveMessage(second.DeviceID, "new", "", "body")
	if err != nil || next.Sequence <= a.Sequence {
		t.Fatal("sequence reused or device lost", err)
	}
}
