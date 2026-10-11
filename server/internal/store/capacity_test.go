package store

import (
	"beacon.local/server/internal/domain"
	"errors"
	"path/filepath"
	"strings"
	"testing"
)

func TestPendingCapacityRejectsAtomicallyAndAckReleasesSlot(t *testing.T) {
	a, err := Open(filepath.Join(t.TempDir(), "capacity.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	d, err := a.CreateDevice("capacity")
	if err != nil {
		t.Fatal(err)
	}
	_, err = a.db.Exec(`WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<?)
 INSERT INTO messages(id,device,title,body,created) SELECT printf('%064x',x),?,'','body',1 FROM n`, domain.MaxPendingPerDevice, d.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = a.SaveMessage(d.DeviceID, "", "", "new"); !errors.Is(err, domain.ErrStorageCapacity) {
		t.Fatalf("capacity result: %v", err)
	}
	var count int
	if err = a.db.QueryRow("SELECT count(*) FROM publish_outbox").Scan(&count); err != nil || count != 0 {
		t.Fatal("rejected write created outbox", err)
	}
	if err = a.Ack(d.DeviceID, []string{strings.Repeat("0", 63) + "1"}); err != nil {
		t.Fatal(err)
	}
	if _, err = a.SaveMessage(d.DeviceID, "", "", "new"); err != nil {
		t.Fatal(err)
	}
}

func TestGlobalHistoryCapacityAndRevocationPrecedePush(t *testing.T) {
	a, err := Open(filepath.Join(t.TempDir(), "global-capacity.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	one, err := a.CreateDevice("history")
	if err != nil {
		t.Fatal(err)
	}
	two, err := a.CreateDevice("target")
	if err != nil {
		t.Fatal(err)
	}
	_, err = a.db.Exec(`WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<?)
INSERT INTO messages(id,device,title,body,created,acked) SELECT printf('%064x',x),?,'','body',1,1 FROM n`, domain.MaxStoredMessages, one.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = a.SaveMessage(two.DeviceID, "", "", "new"); !errors.Is(err, domain.ErrStorageCapacity) {
		t.Fatalf("global capacity result: %v", err)
	}
	if err = a.RevokeDevice(two.DeviceID); err != nil {
		t.Fatal(err)
	}
	if _, err = a.SaveMessage(two.DeviceID, "", "", "new"); !errors.Is(err, domain.ErrInvalidDevice) {
		t.Fatalf("revocation must precede capacity: %v", err)
	}
	if _, err = a.CleanupConfirmed(2, true); err != nil {
		t.Fatal(err)
	}
	if _, err = a.SaveMessage(one.DeviceID, "", "", "after cleanup"); err != nil {
		t.Fatal(err)
	}
}
