package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"sync"
	"time"

	"beacon.local/server/internal/auth"
	"beacon.local/server/internal/domain"
	"beacon.local/server/internal/service"
	"github.com/gorilla/websocket"
)

// StreamHub is a bounded live transport. SQLite and HTTP receipts remain authoritative.
type StreamHub struct {
	app     *service.Service
	mu      sync.Mutex
	clients map[*streamClient]struct{}
	closed  bool
	done    chan struct{}
	stop    sync.Once
}

var streamWriteBuffers sync.Pool

// Covers the 300-second adaptive interval plus its acknowledgement timeout and scheduling slack.
// Legacy clients keep their existing Ping/Pong; no server ping timer is needed.
const streamIdleTimeout = 450 * time.Second

type streamClient struct {
	conn                    *websocket.Conn
	device, token, clientID string
	write                   sync.Mutex
	batch                   bool
	pending                 bool
}

func NewStreamHub(app *service.Service) *StreamHub {
	h := &StreamHub{app: app, clients: make(map[*streamClient]struct{}), done: make(chan struct{})}
	go h.checkAuthorization()
	return h
}

// One timer for the hub instead of one goroutine and ticker per idle connection.
func (h *StreamHub) checkAuthorization() {
	check := time.NewTicker(10 * time.Second)
	defer check.Stop()
	for {
		select {
		case <-h.done:
			return
		case <-check.C:
			for _, c := range h.snapshot() {
				if !h.authorized(c) {
					c.conn.Close()
				}
			}
		}
	}
}

func (h *StreamHub) Handler(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodGet) {
		return
	}
	// Credentials belong in headers, never URLs; this endpoint is for native clients.
	if r.URL.RawQuery != "" || r.URL.ForceQuery || r.Header.Get("Origin") != "" {
		apiError(w, http.StatusBadRequest, "invalid_stream_request")
		return
	}
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	device, err := h.app.Authenticate(token, false)
	if err != nil {
		apiError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	_, err = h.app.RealtimeConfiguration(device, token)
	if err != nil {
		apiError(w, http.StatusServiceUnavailable, "realtime_unavailable")
		return
	}
	h.mu.Lock()
	count := 0
	for c := range h.clients {
		if c.device == device {
			count++
		}
	}
	if h.closed || len(h.clients) >= 256 || count >= 4 {
		h.mu.Unlock()
		apiError(w, http.StatusServiceUnavailable, "stream_capacity")
		return
	}
	upgrader := websocket.Upgrader{HandshakeTimeout: 5 * time.Second, ReadBufferSize: 1024, WriteBufferSize: 4096, WriteBufferPool: &streamWriteBuffers}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		h.mu.Unlock()
		return
	}
	c := &streamClient{conn: conn, device: device, token: token, clientID: domain.ReceiverClientID(device, auth.HashToken(token))}
	c.batch = r.Header.Get("X-Beacon-Heartbeat") == "1" && r.Header.Get("X-Beacon-Reception") == "batch"
	h.clients[c] = struct{}{}
	h.mu.Unlock()
	defer func() { conn.Close(); h.mu.Lock(); delete(h.clients, c); h.mu.Unlock() }()
	conn.SetReadLimit(1024)
	_ = conn.SetReadDeadline(time.Now().Add(streamIdleTimeout))
	conn.SetPongHandler(func(string) error { return conn.SetReadDeadline(time.Now().Add(streamIdleTimeout)) })
	conn.SetPingHandler(func(payload string) error {
		if err := conn.SetReadDeadline(time.Now().Add(streamIdleTimeout)); err != nil {
			return err
		}
		return conn.WriteControl(websocket.PongMessage, []byte(payload), time.Now().Add(5*time.Second))
	})
	// Only the negotiated, bounded heartbeat control is accepted; receipts remain HTTP.
	for {
		kind, payload, readErr := conn.ReadMessage()
		if readErr != nil || kind != websocket.TextMessage || string(payload) != "@beacon/heartbeat/1" || r.Header.Get("X-Beacon-Heartbeat") != "1" {
			return
		}
		if !h.authorized(c) {
			return
		}
		_ = conn.SetReadDeadline(time.Now().Add(streamIdleTimeout))
		c.write.Lock()
		_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		err = conn.WriteMessage(websocket.TextMessage, []byte("@beacon/heartbeat_ack/1"))
		if err == nil && c.pending {
			var page domain.MessagePage
			page, err = h.app.Messages(c.device, 0, 100, true)
			if err == nil {
				c.pending = page.HasMore
				for _, item := range page.Messages {
					var packet []byte
					packet, err = json.Marshal(domain.RealtimeEnvelope{Protocol: domain.RealtimeEnvelopeProtocol, ServerID: h.app.ServerID(), Message: item})
					if err != nil {
						break
					}
					_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
					if err = conn.WriteMessage(websocket.TextMessage, packet); err != nil {
						break
					}
				}
			}
		}
		c.write.Unlock()
		if err != nil {
			return
		}
	}
}

func (h *StreamHub) authorized(c *streamClient) bool {
	id, err := h.app.Authenticate(c.token, false)
	return err == nil && id == c.device
}

func (h *StreamHub) snapshot() []*streamClient {
	h.mu.Lock()
	defer h.mu.Unlock()
	clients := make([]*streamClient, 0, len(h.clients))
	for c := range h.clients {
		clients = append(clients, c)
	}
	return clients
}

// A successful write retires only the outbox task, never the device receipt.
// A lost write is recovered by the client's existing HTTP catch-up on connection.
func (h *StreamHub) Publish(ctx context.Context, serverID, deviceID string, message domain.Message) error {
	if serverID != h.app.ServerID() {
		return domain.ErrInvalidDelivery
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	payload, err := json.Marshal(domain.RealtimeEnvelope{Protocol: domain.RealtimeEnvelopeProtocol, ServerID: serverID, Message: message})
	if err != nil {
		return err
	}
	if len(payload) > domain.MaxRequestBytes {
		return domain.ErrInvalidMessage
	}
	delivered := false
	for _, c := range h.snapshot() {
		if c.device != deviceID {
			continue
		}
		if !h.authorized(c) {
			c.conn.Close()
			continue
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		deadline := time.Now().Add(5 * time.Second)
		if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
			deadline = d
		}
		c.write.Lock()
		if c.batch && message.Priority < 8 {
			// Keep only a dirty flag. SQLite owns bodies, TTL, deletion and receipt state.
			c.pending = true
			c.write.Unlock()
			delivered = true
			continue
		}
		_ = c.conn.SetWriteDeadline(deadline)
		err = c.conn.WriteMessage(websocket.TextMessage, payload)
		c.write.Unlock()
		if err == nil {
			delivered = true
		} else {
			c.conn.Close()
		}
	}
	if !delivered {
		return errors.New("receiver offline")
	}
	return nil
}

func (h *StreamHub) Disconnect(ctx context.Context, clientID string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	for _, c := range h.snapshot() {
		if c.clientID == clientID {
			c.conn.Close()
		}
	}
	return nil
}

func (h *StreamHub) Close() {
	h.stop.Do(func() { close(h.done) })
	h.mu.Lock()
	h.closed = true
	h.mu.Unlock()
	for _, c := range h.snapshot() {
		c.conn.Close()
	}
}
