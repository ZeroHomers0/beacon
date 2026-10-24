package store

import (
	"beacon.local/server/internal/domain"
	"errors"
	"path/filepath"
	"testing"
	"time"
)

func TestStateSnapshotsAndExpiryAreConsistent(t *testing.T) {
	s, err := Open(filepath.Join(t.TempDir(), "states.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	device, err := s.CreateDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	p := domain.StatePayload{Mode: "state", SchemaVersion: 1, TopicKey: "a", Revision: 1, Body: "first"}
	if _, err = s.SaveState(device.PushKey, p); err != nil {
		t.Fatal(err)
	}
	p.TopicKey = "b"
	if _, err = s.SaveState(device.PushKey, p); err != nil {
		t.Fatal(err)
	}
	first, err := s.StatePage(device.DeviceID, "", "", "", 1)
	if err != nil {
		t.Fatal(err)
	}
	token := first["snapshot_token"].(string)
	page := first["page_cursor"].(string)
	p.Revision = 2
	p.Body = "newer"
	if _, err = s.SaveState(device.PushKey, p); err != nil {
		t.Fatal(err)
	}
	second, err := s.StatePage(device.DeviceID, "", token, page, 1)
	if err != nil {
		t.Fatal(err)
	}
	if second["states"].([]domain.StateRecord)[0].Revision != 1 {
		t.Fatal("snapshot changed between pages")
	}
	delta, err := s.StatePage(device.DeviceID, second["next_cursor"].(string), "", "", 100)
	if err != nil {
		t.Fatal(err)
	}
	if records := delta["states"].([]domain.StateRecord); len(records) != 1 || records[0].Revision != 2 {
		t.Fatal("lost concurrent change", records)
	}
	other, _ := s.CreateDevice("other")
	if _, err = s.StatePage(other.DeviceID, delta["next_cursor"].(string), "", "", 100); !errors.Is(err, ErrStateCursorExpired) {
		t.Fatal("cross-device cursor accepted", err)
	}
	if _, err = s.db.Exec("UPDATE current_states SET expires=? WHERE device=? AND topic='b'", time.Now().UnixMilli()-1, device.DeviceID); err != nil {
		t.Fatal(err)
	}
	expired, err := s.StatePage(device.DeviceID, delta["next_cursor"].(string), "", "", 100)
	if err != nil {
		t.Fatal(err)
	}
	records := expired["states"].([]domain.StateRecord)
	if len(records) != 1 || !records[0].Deleted {
		t.Fatal("expiry not synchronized", records)
	}
	if _, err = s.SaveState(device.PushKey, p); err != nil {
		t.Fatal("retry of expired revision should remain idempotent", err)
	}
	current, err := s.StatePage(device.DeviceID, "", "", "", 100)
	if err != nil || len(current["states"].([]domain.StateRecord)) != 1 {
		t.Fatal("retry resurrected expired state", err)
	}
}

func TestApplicationDeletionRemovesOnlyItsOwnStates(t *testing.T) {
	s, err := Open(filepath.Join(t.TempDir(), "app-states.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	device, _ := s.CreateDevice("phone")
	app, _ := s.CreateApplication(device.DeviceID, "app", 5)
	p := domain.StatePayload{Mode: "state", SchemaVersion: 1, TopicKey: "same", Revision: 1, Body: "current"}
	if _, err = s.SaveState(app.Token, p); err != nil {
		t.Fatal(err)
	}
	if _, err = s.SaveState(device.PushKey, p); err != nil {
		t.Fatal(err)
	}
	initial, err := s.StatePage(device.DeviceID, "", "", "", 100)
	if err != nil {
		t.Fatal(err)
	}
	if err = s.RevokeApplication(app.ID); err != nil {
		t.Fatal(err)
	}
	if _, err = s.DeleteApplication(app.ID); err != nil {
		t.Fatal(err)
	}
	page, err := s.StatePage(device.DeviceID, initial["next_cursor"].(string), "", "", 100)
	if err != nil {
		t.Fatal(err)
	}
	if records := page["states"].([]domain.StateRecord); len(records) != 1 || !records[0].Deleted || records[0].ApplicationID != app.ID {
		t.Fatal("wrong deletion", records)
	}
	latest, err := s.StatePage(device.DeviceID, "", "", "", 100)
	if err != nil || len(latest["states"].([]domain.StateRecord)) != 1 {
		t.Fatal("other namespace removed", err)
	}
}
