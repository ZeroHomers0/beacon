package httpapi

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"beacon.local/server/internal/auth"
	"beacon.local/server/internal/domain"
	"github.com/gorilla/websocket"
)

func TestBatchReceptionWaitsForHeartbeatAndUrgentBypassesIt(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "batch-stream.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	a.service = a.service.WithWebSocket()
	hub := NewStreamHub(a.service)
	defer hub.Close()
	server := httptest.NewServer(a.WithStream(hub).Handler())
	defer server.Close()
	device, err := a.createDevice("batch")
	if err != nil {
		t.Fatal(err)
	}
	conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http")+"/api/v1/realtime/stream", http.Header{
		"Authorization": {"Bearer " + device.ReceiveToken}, "X-Beacon-Heartbeat": {"1"}, "X-Beacon-Reception": {"batch"},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	ordinary, err := a.service.Push(device.DeviceID, "ordinary", "", "body")
	if err != nil {
		t.Fatal(err)
	}
	if err = hub.Publish(context.Background(), a.serverID, device.DeviceID, ordinary); err != nil {
		t.Fatal(err)
	}
	priority := 8
	urgent, err := a.service.PushPriority(device.DeviceID, "urgent", "", "body", &priority)
	if err != nil {
		t.Fatal(err)
	}
	if err = hub.Publish(context.Background(), a.serverID, device.DeviceID, urgent); err != nil {
		t.Fatal(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, payload, err := conn.ReadMessage()
	var envelope domain.RealtimeEnvelope
	if err != nil || json.Unmarshal(payload, &envelope) != nil || envelope.Message.ID != urgent.ID {
		t.Fatal("ordinary message did not wait for heartbeat", err)
	}
	if err = conn.WriteMessage(websocket.TextMessage, []byte("@beacon/heartbeat/1")); err != nil {
		t.Fatal(err)
	}
	_, payload, err = conn.ReadMessage()
	if err != nil || string(payload) != "@beacon/heartbeat_ack/1" {
		t.Fatal("missing heartbeat acknowledgement", err)
	}
	_, payload, err = conn.ReadMessage()
	if err != nil || json.Unmarshal(payload, &envelope) != nil || envelope.Message.ID != ordinary.ID {
		t.Fatal("missing batch", err)
	}
	page, err := a.service.Messages(device.DeviceID, 0, 100, true)
	if err != nil || len(page.Messages) != 2 {
		t.Fatal("batch must not acknowledge messages", err)
	}
	// Drain the pending urgent replay, then prove a queued body is not cached past deletion.
	if _, _, err = conn.ReadMessage(); err != nil {
		t.Fatal(err)
	}
	extra, err := a.service.Push(device.DeviceID, "delete-before-heartbeat", "", "body")
	if err != nil {
		t.Fatal(err)
	}
	if err = hub.Publish(context.Background(), a.serverID, device.DeviceID, extra); err != nil {
		t.Fatal(err)
	}
	if _, err = a.service.ClearMessages(device.DeviceID, false); err != nil {
		t.Fatal(err)
	}
	if err = conn.WriteMessage(websocket.TextMessage, []byte("@beacon/heartbeat/1")); err != nil {
		t.Fatal(err)
	}
	_, payload, err = conn.ReadMessage()
	if err != nil || string(payload) != "@beacon/heartbeat_ack/1" {
		t.Fatal("missing acknowledgement after deletion", err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	if _, _, err = conn.ReadMessage(); err == nil {
		t.Fatal("deleted message escaped the durable store")
	}
}

// Explicit long regression: the first ping must survive both the old 150-second server
// deadline. Proxy timeout settings are checked separately. Run with BEACON_LONG_HEARTBEAT_TEST=1.
func TestWebSocketLongestHeartbeat(t *testing.T) {
	if os.Getenv("BEACON_LONG_HEARTBEAT_TEST") != "1" {
		t.Skip("opt-in 181-second idle connection regression")
	}
	a, err := openAPI(filepath.Join(t.TempDir(), "long-heartbeat.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	a.service = a.service.WithWebSocket()
	hub := NewStreamHub(a.service)
	defer hub.Close()
	server := httptest.NewServer(a.WithStream(hub).Handler())
	defer server.Close()
	device, err := a.createDevice("long-heartbeat")
	if err != nil {
		t.Fatal(err)
	}
	conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http")+"/api/v1/realtime/stream", http.Header{"Authorization": {"Bearer " + device.ReceiveToken}})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	pong := make(chan string, 1)
	conn.SetPongHandler(func(value string) error { pong <- value; return nil })
	type result struct {
		body []byte
		err  error
	}
	received := make(chan result, 1)
	go func() { _, body, err := conn.ReadMessage(); received <- result{body, err} }()
	select {
	case early := <-received:
		t.Fatalf("idle socket closed before first heartbeat: %v", early.err)
	case <-time.After(181 * time.Second):
	}
	if err := conn.WriteControl(websocket.PingMessage, []byte("heartbeat"), time.Now().Add(2*time.Second)); err != nil {
		t.Fatal(err)
	}
	select {
	case value := <-pong:
		if value != "heartbeat" {
			t.Fatal("wrong pong")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("heartbeat was not answered")
	}
	message, err := a.service.Push(device.DeviceID, "after idle", "", "body")
	if err != nil {
		t.Fatal(err)
	}
	if err := hub.Publish(context.Background(), a.serverID, device.DeviceID, message); err != nil {
		t.Fatal(err)
	}
	select {
	case delivery := <-received:
		var envelope domain.RealtimeEnvelope
		if delivery.err != nil || json.Unmarshal(delivery.body, &envelope) != nil || envelope.Message.ID != message.ID {
			t.Fatal("delivery after idle failed", delivery.err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("push waited for another heartbeat")
	}
}

func TestWebSocketDeliveryAndRevocation(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "stream.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	a.service = a.service.WithWebSocket()
	hub := NewStreamHub(a.service)
	defer hub.Close()
	server := httptest.NewServer(a.WithStream(hub).Handler())
	defer server.Close()
	device, err := a.createDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	url := "ws" + strings.TrimPrefix(server.URL, "http") + "/api/v1/realtime/stream"
	conn, _, err := websocket.DefaultDialer.Dial(url, http.Header{"Authorization": {"Bearer " + device.ReceiveToken}})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	message, err := a.service.Push(device.DeviceID, "title", "subtitle", "body")
	if err != nil {
		t.Fatal(err)
	}
	if err := hub.Publish(context.Background(), a.serverID, device.DeviceID, message); err != nil {
		t.Fatal(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, payload, err := conn.ReadMessage()
	if err != nil {
		t.Fatal(err)
	}
	var envelope domain.RealtimeEnvelope
	if json.Unmarshal(payload, &envelope) != nil || envelope.Message.ID != message.ID || envelope.ServerID != a.serverID {
		t.Fatal("wrong envelope")
	}
	page, err := a.service.Messages(device.DeviceID, 0, 10, true)
	if err != nil || len(page.Messages) != 1 {
		t.Fatal("stream write incorrectly confirmed receipt")
	}
	if err := a.service.RevokeDevice(device.DeviceID); err != nil {
		t.Fatal(err)
	}
	if err := hub.Disconnect(context.Background(), domain.ReceiverClientID(device.DeviceID, auth.HashToken(device.ReceiveToken))); err != nil {
		t.Fatal(err)
	}
	if _, _, err := conn.ReadMessage(); err == nil {
		t.Fatal("revoked connection remains active")
	}
	if err := hub.Publish(context.Background(), a.serverID, device.DeviceID, message); err == nil {
		t.Fatal("revoked device received publication")
	}
}

func TestWebSocketIdleRevocationKeepsOtherDeviceConnected(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "idle-stream.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	a.service = a.service.WithWebSocket()
	hub := NewStreamHub(a.service)
	defer hub.Close()
	server := httptest.NewServer(a.WithStream(hub).Handler())
	defer server.Close()
	url := "ws" + strings.TrimPrefix(server.URL, "http") + "/api/v1/realtime/stream"
	revoked, err := a.createDevice("revoked")
	if err != nil {
		t.Fatal(err)
	}
	active, err := a.createDevice("active")
	if err != nil {
		t.Fatal(err)
	}
	idle, _, err := websocket.DefaultDialer.Dial(url, http.Header{"Authorization": {"Bearer " + revoked.ReceiveToken}})
	if err != nil {
		t.Fatal(err)
	}
	defer idle.Close()
	live, _, err := websocket.DefaultDialer.Dial(url, http.Header{"Authorization": {"Bearer " + active.ReceiveToken}})
	if err != nil {
		t.Fatal(err)
	}
	defer live.Close()
	if err := a.service.RevokeDevice(revoked.DeviceID); err != nil {
		t.Fatal(err)
	}
	// No delivery or disconnect worker is running. The shared guard must revoke idle sockets.
	_ = idle.SetReadDeadline(time.Now().Add(12 * time.Second))
	if _, _, err := idle.ReadMessage(); err == nil {
		t.Fatal("idle revoked connection was not closed by the shared guard")
	} else if networkError, ok := err.(net.Error); ok && networkError.Timeout() {
		t.Fatal("idle revoked connection reached its read timeout")
	}
	message, err := a.service.Push(active.DeviceID, "still active", "", "body")
	if err != nil {
		t.Fatal(err)
	}
	if err := hub.Publish(context.Background(), a.serverID, active.DeviceID, message); err != nil {
		t.Fatal(err)
	}
	_ = live.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := live.ReadMessage(); err != nil {
		t.Fatal("another device was disconnected", err)
	}
	hub.Close() // Closing an already closed hub remains safe.
}

func TestWebSocketRejectsPushCredentialQueriesAndBrowserOrigin(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "stream.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	a.service = a.service.WithWebSocket()
	hub := NewStreamHub(a.service)
	defer hub.Close()
	server := httptest.NewServer(a.WithStream(hub).Handler())
	defer server.Close()
	device, err := a.createDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	url := "ws" + strings.TrimPrefix(server.URL, "http") + "/api/v1/realtime/stream"
	for _, test := range []struct {
		suffix, token, origin string
		status                int
	}{
		{"", device.PushKey, "", 401},
		{"?token=" + device.ReceiveToken, device.ReceiveToken, "", 400},
		{"", device.ReceiveToken, "https://foreign.test", 400},
	} {
		header := http.Header{"Authorization": {"Bearer " + test.token}}
		if test.origin != "" {
			header.Set("Origin", test.origin)
		}
		conn, response, err := websocket.DefaultDialer.Dial(url+test.suffix, header)
		if conn != nil {
			conn.Close()
		}
		if err == nil || response == nil || response.StatusCode != test.status {
			t.Fatalf("unexpected handshake: %v", err)
		}
		response.Body.Close()
	}
}
