package store

import (
	"beacon.local/server/internal/domain"
	"fmt"
	"path/filepath"
	"testing"
	"time"
)

func TestThirtyMinuteStateStreamKeepsOnlyCurrentSnapshots(t *testing.T) {
	s, err := Open(filepath.Join(t.TempDir(), "load.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	device, err := s.CreateDevice("load")
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UnixMilli()
	s.stateClock = func() int64 { return now }
	// Twenty topics at ten-second intervals use the advertised 120 writes/minute
	// per application. Virtual time covers thirty minutes without sleeping.
	for tick := 0; tick < 180; tick++ {
		for topic := 0; topic < 20; topic++ {
			p := domain.StatePayload{Mode: "state", SchemaVersion: 1, TopicKey: fmt.Sprintf("load:%d", topic), Revision: int64(tick + 1), ObservedAt: now, Body: "latest state"}
			if _, err = saveRegisteredState(t, s, device.PushKey, p); err != nil {
				t.Fatalf("tick %d topic %d: %v", tick, topic, err)
			}
		}
		now += 10000
	}
	var current, events, changes int
	s.db.QueryRow("SELECT count(*) FROM current_states WHERE device=?", device.DeviceID).Scan(&current)
	s.db.QueryRow("SELECT count(*) FROM messages WHERE device=?", device.DeviceID).Scan(&events)
	s.db.QueryRow("SELECT count(*) FROM state_changes WHERE device=?", device.DeviceID).Scan(&changes)
	if current != 20 || events != 0 || changes > 10000 {
		t.Fatalf("unbounded storage: states=%d events=%d log=%d", current, events, changes)
	}
}
