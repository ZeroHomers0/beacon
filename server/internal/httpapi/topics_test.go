package httpapi

import (
	"encoding/json"
	"github.com/gorilla/websocket"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestTopicManagementRequiresAdministratorAndDoesNotAutoCreate(t *testing.T) {
	a, d, _, _ := setupAPI(t)
	admin := strings.Repeat("b", 64)
	a.WithWebAdmin(admin)
	payload := map[string]any{"device_id": d.DeviceID, "topic_key": "empty", "name": "Empty", "request_id": "one"}
	for _, credential := range []string{d.PushKey, d.ReceiveToken} {
		w := requestAPI(a, "POST", "/api/admin/topics/create", credential, payload)
		if w.Code != 401 {
			t.Fatal(w.Code, w.Body)
		}
	}
	data := map[string]any{"mode": "state", "schema_version": 1, "topic_key": "empty", "revision": 1, "body": "data"}
	if w := requestAPI(a, "POST", "/api/v1/push", d.PushKey, data); w.Code != 404 {
		t.Fatal(w.Code, w.Body)
	}
	if w := requestAPI(a, "POST", "/api/admin/topics/create", admin, payload); w.Code != 200 {
		t.Fatal(w.Code, w.Body)
	}
	if w := requestAPI(a, "GET", "/api/v1/topics", d.PushKey, nil); w.Code != 401 {
		t.Fatal(w.Code, w.Body)
	}
	w := requestAPI(a, "GET", "/api/v1/topics", d.ReceiveToken, nil)
	var page map[string]any
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body)
	}
	json.Unmarshal(w.Body.Bytes(), &page)
	if len(page["topics"].([]any)) != 1 {
		t.Fatal(page)
	}
	if w := requestAPI(a, "POST", "/api/v1/push", d.PushKey, data); w.Code != 200 {
		t.Fatal(w.Code, w.Body)
	}
	deletePayload := map[string]any{"device_id": d.DeviceID, "topic_key": "empty", "config_version": 1}
	if w := requestAPI(a, "POST", "/api/admin/topics/delete", admin, deletePayload); w.Code != 200 {
		t.Fatal(w.Code, w.Body)
	}
	if w := requestAPI(a, "POST", "/api/v1/push", d.PushKey, data); w.Code != 404 {
		t.Fatal(w.Code, w.Body)
	}
	if w := requestAPI(a, "GET", "/api/v1/states/meta?topic_key=empty", d.PushKey, nil); w.Code != 404 {
		t.Fatal(w.Code, w.Body)
	}
	w = requestAPI(a, "POST", "/api/admin/topics/list", admin, map[string]any{})
	var list map[string]any
	json.Unmarshal(w.Body.Bytes(), &list)
	if w.Code != 200 || list["total"] != float64(0) {
		t.Fatal(w.Code, w.Body)
	}
}

func TestTopicDirectoryHintRequiresNegotiatedCapability(t *testing.T) {
	a, d, _, _ := setupAPI(t)
	admin := strings.Repeat("b", 64)
	a.WithWebAdmin(admin)
	a.service = a.service.WithWebSocket()
	hub := NewStreamHub(a.service)
	defer hub.Close()
	a.WithStream(hub)
	server := httptest.NewServer(a.Handler())
	defer server.Close()
	ws, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http")+"/api/v1/realtime/stream", http.Header{"Authorization": {"Bearer " + d.ReceiveToken}, "X-Beacon-Topics": {"1"}})
	if err != nil {
		t.Fatal(err)
	}
	defer ws.Close()
	w := requestAPI(a, "POST", "/api/admin/topics/create", admin, map[string]any{"device_id": d.DeviceID, "name": "Empty", "topic_key": "empty", "request_id": "hint"})
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body)
	}
	ws.SetReadDeadline(time.Now().Add(time.Second))
	_, raw, err := ws.ReadMessage()
	if err != nil || string(raw) != "@beacon/topics_changed/1" {
		t.Fatal(string(raw), err)
	}
}
