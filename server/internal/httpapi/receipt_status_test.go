package httpapi

import (
	"encoding/json"
	"testing"
)

func TestReceiptAvailabilityNeverConfirmsUnknownOrOtherDeviceRecords(t *testing.T) {
	a, one, other, _ := setupAPI(t)
	message := pushTest(t, a, one)
	body := map[string]any{"ids": []string{message.ID}}
	for _, test := range []struct{ token, status string }{{one.ReceiveToken, "available"}, {other.ReceiveToken, "missing"}} {
		response := requestAPI(a, "POST", "/api/v1/receipts/status", test.token, body)
		var result struct{ Statuses map[string]string }
		if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &result) != nil || result.Statuses[message.ID] != test.status {
			t.Fatalf("availability %d %s", response.Code, response.Body.String())
		}
	}
	var acked int
	if err := a.db.QueryRow("SELECT acked FROM messages WHERE id=?", message.ID).Scan(&acked); err != nil || acked != 0 {
		t.Fatal("status query confirmed message", err)
	}
	if response := requestAPI(a, "POST", "/api/v1/messages/ack", other.ReceiveToken, body); response.Code != 400 {
		t.Fatal("availability weakened ACK isolation")
	}
	if response := requestAPI(a, "POST", "/api/v1/receipts/status", one.PushKey, body); response.Code != 401 {
		t.Fatal("push credential can read receipts")
	}
}
