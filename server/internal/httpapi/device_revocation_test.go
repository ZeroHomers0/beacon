package httpapi

import (
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func TestSelfRevocationIsScopedAndRetryable(t *testing.T) {
	a, one, two, _ := setupAPI(t)
	message := pushTest(t, a, one)
	payload := map[string]string{"server_id": a.serverID, "device_id": one.DeviceID}
	for _, token := range []string{"", one.PushKey, two.ReceiveToken, strings.Repeat("a", 64)} {
		if w := requestAPI(a, "POST", "/api/v1/device/revoke", token, payload); w.Code != 401 {
			t.Fatalf("unauthorized revoke: %d", w.Code)
		}
	}
	if w := requestAPI(a, "POST", "/api/v1/device/revoke", one.ReceiveToken, map[string]string{"server_id": strings.Repeat("f", 64), "device_id": one.DeviceID}); w.Code != 401 {
		t.Fatal("wrong server accepted")
	}
	// Concurrent retries cannot enqueue duplicate disconnects or change device ownership.
	var group sync.WaitGroup
	for i := 0; i < 8; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			if w := requestAPI(a, "POST", "/api/v1/device/revoke", one.ReceiveToken, payload); w.Code != 200 {
				t.Errorf("retry: %d %s", w.Code, w.Body.String())
			}
		}()
	}
	group.Wait()
	for _, path := range []string{"/api/v1/messages", "/api/v1/capabilities"} {
		if w := requestAPI(a, "GET", path, one.ReceiveToken, nil); w.Code != 401 {
			t.Fatal("revoked credential accepted", path)
		}
	}
	if w := requestAPI(a, "POST", "/api/v1/push", one.PushKey, map[string]string{"body": "x"}); w.Code != 401 {
		t.Fatal("revoked push accepted")
	}
	if w := requestAPI(a, "GET", "/api/v1/messages", two.ReceiveToken, nil); w.Code != 200 {
		t.Fatal("other device affected")
	}
	var count, acked int
	if err := a.db.QueryRow("SELECT count(*),coalesce(sum(acked),0) FROM messages WHERE id=?", message.ID).Scan(&count, &acked); err != nil || count != 1 || acked != 0 {
		t.Fatal("history changed", err, count, acked)
	}
	if err := a.db.QueryRow("SELECT count(*) FROM session_disconnects").Scan(&count); err != nil || count != 1 {
		t.Fatal("disconnect not queued once", err, count)
	}
	if err := a.db.QueryRow("SELECT count(*) FROM publish_outbox WHERE message_seq IN (SELECT seq FROM messages WHERE id=?)", message.ID).Scan(&count); err != nil || count != 0 {
		t.Fatal("revoked publish remains", err, count)
	}
	if _, err := a.repository.RotateDevice(one.DeviceID); err == nil {
		t.Fatal("revoked device reactivated")
	}
}

func TestSelfRevocationRejectsOldRotatedCredential(t *testing.T) {
	a, one, _, _ := setupAPI(t)
	replacement, err := a.repository.RotateDevice(one.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	payload := map[string]string{"server_id": a.serverID, "device_id": one.DeviceID}
	if w := requestAPI(a, "POST", "/api/v1/device/revoke", one.ReceiveToken, payload); w.Code != 401 {
		t.Fatal("old token accepted")
	}
	if w := requestAPI(a, "GET", "/api/v1/messages", replacement.ReceiveToken, nil); w.Code != 200 {
		t.Fatal("new device revoked")
	}
	if w := requestAPI(a, "POST", "/api/v1/device/revoke", replacement.ReceiveToken, payload); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
}

func TestSelfRevocationValidationAndTransactionRollback(t *testing.T) {
	a, one, _, _ := setupAPI(t)
	endpoint := "/api/v1/device/revoke"
	for _, test := range []struct {
		body        string
		contentType string
		status      int
	}{
		{`{}`, "application/json", 401},
		{`{"server_id":"x","device_id":"y","extra":1}`, "application/json", 400},
		{strings.Repeat(" ", 1025) + `{}`, "application/json", 400},
		{`{}`, "text/plain", 415},
	} {
		r := httptest.NewRequest("POST", endpoint, strings.NewReader(test.body))
		r.Header.Set("Content-Type", test.contentType)
		r.Header.Set("Authorization", "Bearer "+one.ReceiveToken)
		w := httptest.NewRecorder()
		a.handler().ServeHTTP(w, r)
		if w.Code != test.status {
			t.Fatal(test, w.Code)
		}
	}
	if w := requestAPI(a, "GET", endpoint, one.ReceiveToken, nil); w.Code != 405 {
		t.Fatal("method accepted")
	}
	pushTest(t, a, one)
	// A failure to retire publish tasks rolls back both revocation and the disconnect job.
	if _, err := a.db.Exec("CREATE TRIGGER fail_revoke BEFORE DELETE ON publish_outbox BEGIN SELECT RAISE(ABORT,'fixture'); END"); err != nil {
		t.Fatal(err)
	}
	if w := requestAPI(a, "POST", endpoint, one.ReceiveToken, map[string]string{"server_id": a.serverID, "device_id": one.DeviceID}); w.Code != 500 {
		t.Fatal("transaction unexpectedly committed", w.Code)
	}
	if w := requestAPI(a, "GET", "/api/v1/messages", one.ReceiveToken, nil); w.Code != 200 {
		t.Fatal("credential lost after rollback")
	}
	var count int
	if err := a.db.QueryRow("SELECT count(*) FROM session_disconnects").Scan(&count); err != nil || count != 0 {
		t.Fatal("disconnect escaped rollback", err, count)
	}
}
