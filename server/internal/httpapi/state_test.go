package httpapi

import (
	"encoding/json"
	"github.com/gorilla/websocket"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestStatePushAndSnapshotSync(t *testing.T) {
	a, device, _, _ := setupAPI(t)
	payload := map[string]any{"mode": "state", "schema_version": 1, "topic_key": "download:1", "revision": 1, "state_type": "task", "status": "running", "body": "40%", "blocks": []any{map[string]any{"kind": "progress", "id": "download", "value": 40, "max": 100}}}
	send := func(p any, intended int) map[string]any {
		t.Helper()
		w := requestAPI(a, "POST", "/api/v1/push", device.PushKey, p)
		if w.Code != intended {
			t.Fatalf("status=%d body=%s", w.Code, w.Body)
		}
		var result map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		return result
	}
	send(payload, 200)
	if send(payload, 200)["unchanged"] != true {
		t.Fatal("retry not idempotent")
	}
	first := requestAPI(a, "GET", "/api/v1/states", device.ReceiveToken, nil)
	if first.Code != 200 {
		t.Fatal(first.Code, first.Body)
	}
	var page map[string]any
	json.Unmarshal(first.Body.Bytes(), &page)
	if len(page["states"].([]any)) != 1 {
		t.Fatal(page)
	}
	cursor := page["next_cursor"].(string)
	payload["body"] = "41%"
	send(payload, 409)
	payload["revision"] = 2
	payload["status"] = "completed"
	send(payload, 200)
	updated := requestAPI(a, "GET", "/api/v1/states?cursor="+url.QueryEscape(cursor), device.ReceiveToken, nil)
	if updated.Code != 200 {
		t.Fatal(updated.Code, updated.Body)
	}
	payload["revision"] = 3
	payload["status"] = "running"
	send(payload, 409)
	send(map[string]any{"mode": "state", "schema_version": 1, "operation": "delete", "topic_key": "download:1", "revision": 3}, 200)
	empty := requestAPI(a, "GET", "/api/v1/states", device.ReceiveToken, nil)
	json.Unmarshal(empty.Body.Bytes(), &page)
	if len(page["states"].([]any)) != 0 {
		t.Fatal(page)
	}
	events, err := a.repository.Messages(device.DeviceID, 0, 100, false)
	if err != nil || len(events.Messages) != 0 {
		t.Fatal("state polluted events", events, err)
	}
	denied := requestAPI(a, "GET", "/api/v1/states", device.PushKey, nil)
	if denied.Code != 401 {
		t.Fatal("push key read states")
	}
}

func TestNegotiatedStateHint(t *testing.T) {
	a, device, _, _ := setupAPI(t)
	a.service = a.service.WithWebSocket()
	hub := NewStreamHub(a.service)
	defer hub.Close()
	server := httptest.NewServer(a.WithStream(hub).Handler())
	defer server.Close()
	ws, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http")+"/api/v1/realtime/stream", http.Header{"Authorization": {"Bearer " + device.ReceiveToken}, "X-Beacon-State": {"1"}})
	if err != nil {
		t.Fatal(err)
	}
	defer ws.Close()
	response := requestAPI(a, "POST", "/api/v1/push", device.PushKey, map[string]any{"mode": "state", "schema_version": 1, "topic_key": "progress", "revision": 1, "body": "running"})
	if response.Code != 200 {
		t.Fatal(response.Code, response.Body)
	}
	ws.SetReadDeadline(time.Now().Add(time.Second))
	_, raw, err := ws.ReadMessage()
	if err != nil || string(raw) != "@beacon/state_changed/1" {
		t.Fatal("missing state hint", string(raw), err)
	}
}

func TestStateRejectsForbiddenPresence(t *testing.T) {
	a, d, _, _ := setupAPI(t)
	for _, p := range []map[string]any{
		{"mode": "event", "body": "event", "revision": 0},
		{"mode": "state", "schema_version": 1, "topic_key": "x", "revision": 1, "operation": "delete", "title": ""},
		{"mode": nil, "body": "event"},
		{"schema_version": nil, "body": "event"},
	} {
		w := requestAPI(a, "POST", "/api/v1/push", d.PushKey, p)
		if w.Code != 400 {
			t.Fatalf("accepted forbidden fields: %v (%d)", p, w.Code)
		}
	}
}
