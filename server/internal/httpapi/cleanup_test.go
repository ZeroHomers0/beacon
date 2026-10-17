package httpapi

import (
	"encoding/json"
	"testing"
	"time"

	"beacon.local/server/internal/domain"
)

func TestHTTPConfirmationRetryAfterCleanup(t *testing.T) {
	api, device, other, _ := setupAPI(t)
	confirmed := pushTest(t, api, device)
	pending := pushTest(t, api, device)
	for _, message := range []wireMessage{confirmed, pending} {
		if _, err := api.db.Exec("UPDATE messages SET created=? WHERE id=?", time.Now().Add(-time.Hour).UnixMilli(), message.ID); err != nil {
			t.Fatal(err)
		}
	}
	payload := map[string]any{"ids": []string{confirmed.ID}}
	if response := requestAPI(api, "POST", "/api/v1/messages/ack", device.ReceiveToken, payload); response.Code != 200 {
		t.Fatal("initial confirmation failed")
	}
	if result, err := api.service.CleanupConfirmed(time.Now().UnixMilli(), true); err != nil || result.Deleted != 1 || result.ProtectedPending != 1 {
		t.Fatalf("cleanup failed: %+v, %v", result, err)
	}
	if response := requestAPI(api, "POST", "/api/v1/messages/ack", device.ReceiveToken, payload); response.Code != 200 {
		t.Fatal("confirmation retry failed after cleanup")
	}
	if response := requestAPI(api, "POST", "/api/v1/messages/ack", other.ReceiveToken, payload); response.Code != 400 {
		t.Fatal("another device confirmed a cleaned message")
	}
	response := requestAPI(api, "GET", "/api/v1/messages?pending=1", device.ReceiveToken, nil)
	var page domain.MessagePage
	if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &page) != nil || len(page.Messages) != 1 || page.Messages[0].ID != pending.ID {
		t.Fatal("pending receive changed after cleanup")
	}
}
