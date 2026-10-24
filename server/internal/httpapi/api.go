// Package httpapi translates HTTP requests into application operations.
package httpapi

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"runtime/debug"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"beacon.local/server/internal/domain"
	"beacon.local/server/internal/service"
)

func init() {
	if os.Getenv("GIN_MODE") == "" {
		gin.SetMode(gin.ReleaseMode)
	}
}

type API struct {
	service      *service.Service
	bindingLimit bindingLimiter
	stream       *StreamHub
	adminToken   string
	metrics      *requestMetrics
}

func New(service *service.Service) *API {
	return &API{service: service, metrics: newRequestMetrics()}
}

func (a *API) WithStream(stream *StreamHub) *API { a.stream = stream; return a }

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func apiError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}

func decodeJSON(w http.ResponseWriter, r *http.Request, value any) error {
	r.Body = http.MaxBytesReader(w, r.Body, domain.MaxRequestBytes)
	defer r.Body.Close()
	payload, err := io.ReadAll(r.Body)
	if err != nil {
		return err
	}
	if !validJSONUnicode(payload) {
		return errors.New("invalid UTF-8")
	}
	d := json.NewDecoder(bytes.NewReader(payload))
	d.DisallowUnknownFields()
	if err := d.Decode(value); err != nil {
		return err
	}
	var extra any
	if err := d.Decode(&extra); err != io.EOF {
		return errors.New("trailing payload")
	}
	return nil
}

// bearerToken extracts the Bearer credential and validates only its shape, so
// callers can reject a malformed header before reading a request body.
func bearerToken(r *http.Request) (string, bool) {
	header := r.Header.Get("Authorization")
	if !strings.HasPrefix(header, "Bearer ") {
		return "", false
	}
	token := strings.TrimPrefix(header, "Bearer ")
	if len(token) != domain.TokenLength {
		return "", false
	}
	return token, true
}

func (a *API) authenticate(r *http.Request, push bool) (string, error) {
	token, ok := bearerToken(r)
	if !ok {
		return "", domain.ErrUnauthorized
	}
	return a.service.Authenticate(token, push)
}

func (a *API) Handler() http.Handler {
	router := gin.New()
	// Do not log RequestURI: Bark paths contain credentials and message contents.
	// Disable automatic redirects so a misspelled endpoint cannot forward secrets.
	router.RedirectTrailingSlash = false
	router.RedirectFixedPath = false
	_ = router.SetTrustedProxies(nil)
	// Registered first so it also sees requests that panic: it wraps the recovery middleware.
	router.Use(metricsMiddleware(a.metrics))
	router.Use(compressJSON())
	router.Use(func(c *gin.Context) {
		defer func() {
			if recovered := recover(); recovered != nil {
				// Route templates identify the endpoint without echoing Bark paths,
				// which carry credentials. The stack stays in the server log only.
				log.Printf("panic recovered: route=%s recovered=%v\n%s", c.FullPath(), recovered, debug.Stack())
				if !c.Writer.Written() {
					apiError(c.Writer, http.StatusInternalServerError, "internal_error")
				}
				c.Abort()
			}
		}()
		c.Next()
	})
	router.Any("/healthz", gin.WrapF(HealthHandler))
	v1 := router.Group("/api/v1")
	v1.Any("/capabilities", gin.WrapF(a.capabilities))
	v1.Any("/receipts/status", gin.WrapF(a.receiptStatus))
	v1.Any("/realtime/config", gin.WrapF(a.realtimeConfiguration))
	if a.stream != nil {
		v1.Any("/realtime/stream", gin.WrapF(a.stream.Handler))
	}
	v1.Any("/bind", gin.WrapF(a.bind))
	v1.Any("/device/revoke", gin.WrapF(a.revokeSelf))
	v1.Any("/push", gin.WrapF(a.push))
	v1.Any("/push-capabilities", gin.WrapF(a.pushCapabilities))
	v1.Any("/states", gin.WrapF(a.states))
	v1.Any("/states/meta", gin.WrapF(a.stateMeta))
	v1.Any("/messages", gin.WrapF(a.messages))
	v1.Any("/messages/ack", gin.WrapF(a.ack))
	// Bark splits the original escaped RequestURI, preserving encoded separators.
	router.NoRoute(gin.WrapF(a.bark))
	a.registerAdmin(router)
	return router
}

func requireMethod(w http.ResponseWriter, r *http.Request, method string) bool {
	if r.Method == method {
		return true
	}
	w.Header().Set("Allow", method)
	apiError(w, http.StatusMethodNotAllowed, "method_not_allowed")
	return false
}

func (a *API) capabilities(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, "GET") {
		return
	}
	device, err := a.authenticate(r, false)
	if err != nil {
		apiError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	transports := []string{"http"}
	if a.stream != nil {
		transports = append(transports, "websocket")
	}
	expired, err := a.service.ExpiredMessages(device)
	if err != nil {
		apiError(w, http.StatusInternalServerError, "storage_error")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"protocol":                    domain.ProtocolVersion,
		"server_id":                   a.service.ServerID(),
		"device_id":                   device,
		"server_time_ms":              time.Now().UnixMilli(),
		"transports":                  transports,
		"push_apis":                   []string{"beacon", "bark_text"},
		"message_fields":              []string{"title", "subtitle", "body", "priority", "group", "ttl", "application_id", "application_name", "expires_at_ms", "actions"},
		"max_message_actions":         1,
		"structured_schema_versions":  []int{1},
		"state_protocol":              1,
		"state_sync":                  true,
		"block_kinds":                 domain.BlockKinds,
		"push_idempotency":            true,
		"max_ttl_seconds":             604800,
		"max_page_size":               domain.MaxPageSize,
		"binding":                     "one_time_code",
		"self_revoke":                 true,
		"delivery_window_ms":          domain.PendingLifetime.Milliseconds(),
		"delivery_retention_protocol": domain.DeliveryRetentionProtocol,
		"receipt_status":              true,
		"reception_control":           1,
		"receipt_window_ms":           domain.ReceiptLifetime.Milliseconds(),
		"expired_messages":            expired,
	})
}

func (a *API) push(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, "POST") {
		return
	}
	token, ok := bearerToken(r)
	if !ok {
		apiError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var rawPayload bytes.Buffer
	originalBody := r.Body
	r.Body = struct {
		io.Reader
		io.Closer
	}{io.TeeReader(originalBody, &rawPayload), originalBody}
	var payload struct {
		Mode          string                  `json:"mode"`
		Operation     string                  `json:"operation"`
		TopicKey      string                  `json:"topic_key"`
		Revision      int64                   `json:"revision"`
		ObservedAt    int64                   `json:"observed_at_ms"`
		FreshFor      int64                   `json:"fresh_for_seconds"`
		StateType     string                  `json:"state_type"`
		Status        string                  `json:"status"`
		Title         string                  `json:"title"`
		Body          string                  `json:"body"`
		Subtitle      string                  `json:"subtitle"`
		Priority      *int                    `json:"priority"`
		Group         string                  `json:"group"`
		TTL           int64                   `json:"ttl"`
		Actions       domain.MessageActions   `json:"actions"`
		SchemaVersion int                     `json:"schema_version"`
		Blocks        domain.StructuredBlocks `json:"blocks"`
	}
	if decodeJSON(w, r, &payload) != nil {
		apiError(w, http.StatusBadRequest, "invalid_message")
		return
	}
	var present map[string]json.RawMessage
	if err := json.Unmarshal(rawPayload.Bytes(), &present); err != nil {
		stateError(w, domain.ErrInvalidMessage)
		return
	}
	for _, field := range []string{"mode", "schema_version", "operation", "topic_key", "revision", "observed_at_ms", "fresh_for_seconds", "state_type", "status"} {
		if value, ok := present[field]; ok && bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			stateError(w, domain.ErrInvalidMessage)
			return
		}
	}
	if payload.Mode == "state" && payload.Operation == "delete" {
		for field := range present {
			if field != "mode" && field != "schema_version" && field != "operation" && field != "topic_key" && field != "revision" {
				stateError(w, domain.ErrInvalidMessage)
				return
			}
		}
	}
	if payload.Mode != "state" {
		for _, field := range []string{"operation", "topic_key", "revision", "observed_at_ms", "fresh_for_seconds", "state_type", "status"} {
			if _, ok := present[field]; ok {
				stateError(w, domain.ErrInvalidMessage)
				return
			}
		}
	}
	if payload.Mode == "state" {
		var semantic any
		if err := json.Unmarshal(rawPayload.Bytes(), &semantic); err != nil {
			stateError(w, domain.ErrInvalidMessage)
			return
		}
		canonical, _ := json.Marshal(semantic)
		result, err := a.service.SaveState(token, domain.StatePayload{DigestInput: canonical, Mode: payload.Mode, SchemaVersion: payload.SchemaVersion, Operation: payload.Operation, TopicKey: payload.TopicKey, Revision: payload.Revision, ObservedAt: payload.ObservedAt, FreshFor: payload.FreshFor, StateType: payload.StateType, Status: payload.Status, Title: payload.Title, Subtitle: payload.Subtitle, Body: payload.Body, Priority: payload.Priority, Group: payload.Group, TTL: payload.TTL, Actions: payload.Actions, Blocks: payload.Blocks})
		if err != nil {
			stateError(w, err)
			return
		}
		if a.stream != nil && !result.Unchanged {
			if device, err := a.service.StateDevice(token); err == nil {
				a.stream.StateChanged(device)
			}
		}
		writeJSON(w, 200, result)
		return
	}
	if payload.Mode != "" && payload.Mode != "event" || payload.TopicKey != "" || payload.Revision != 0 || payload.Operation != "" || payload.StateType != "" || payload.Status != "" || payload.FreshFor != 0 || payload.ObservedAt != 0 {
		stateError(w, domain.ErrInvalidMessage)
		return
	}
	m, err := a.service.PushWithOptions(token, payload.Title, payload.Subtitle, payload.Body, payload.Priority, domain.PushOptions{Key: r.Header.Get("Idempotency-Key"), Group: payload.Group, TTL: payload.TTL, Actions: payload.Actions, SchemaVersion: payload.SchemaVersion, Blocks: payload.Blocks})
	if err != nil {
		if errors.Is(err, domain.ErrIdempotencyConflict) {
			apiError(w, http.StatusConflict, "idempotency_conflict")
		} else if errors.Is(err, domain.ErrInvalidMessage) {
			apiError(w, http.StatusBadRequest, "invalid_message")
		} else if errors.Is(err, domain.ErrStorageCapacity) {
			apiError(w, http.StatusInsufficientStorage, "storage_capacity_reached")
		} else if errors.Is(err, domain.ErrRateLimited) {
			w.Header().Set("Retry-After", "1")
			apiError(w, http.StatusTooManyRequests, "push_rate_limited")
		} else if errors.Is(err, domain.ErrInvalidDevice) || errors.Is(err, domain.ErrUnauthorized) {
			apiError(w, http.StatusUnauthorized, "unauthorized")
		} else {
			apiError(w, http.StatusInternalServerError, "storage_error")
		}
		return
	}
	writeJSON(w, http.StatusOK, m)
}

func (a *API) messages(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, "GET") {
		return
	}
	device, err := a.authenticate(r, false)
	if err != nil {
		apiError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	after := int64(0)
	limit := domain.DefaultPageSize
	if s := r.URL.Query().Get("after"); s != "" {
		after, err = strconv.ParseInt(s, 10, 64)
		if err != nil || after < 0 {
			apiError(w, http.StatusBadRequest, "invalid_cursor")
			return
		}
	}
	if s := r.URL.Query().Get("limit"); s != "" {
		limit, err = strconv.Atoi(s)
		if err != nil || limit < 1 || limit > domain.MaxPageSize {
			apiError(w, http.StatusBadRequest, "invalid_limit")
			return
		}
	}
	pending := r.URL.Query().Get("pending")
	if pending != "" && pending != "1" {
		apiError(w, http.StatusBadRequest, "invalid_pending")
		return
	}
	page, err := a.service.Messages(device, after, limit, pending == "1")
	if err != nil {
		apiError(w, http.StatusInternalServerError, "storage_error")
		return
	}
	writeJSON(w, http.StatusOK, page)
}

func (a *API) ack(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, "POST") {
		return
	}
	device, err := a.authenticate(r, false)
	if err != nil {
		apiError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var payload struct {
		IDs []string `json:"ids"`
	}
	if decodeJSON(w, r, &payload) != nil || len(payload.IDs) < 1 || len(payload.IDs) > domain.MaxPageSize {
		apiError(w, http.StatusBadRequest, "invalid_ack")
		return
	}
	if err := a.service.Ack(device, payload.IDs); err != nil {
		if errors.Is(err, domain.ErrUnknownMessage) {
			apiError(w, http.StatusBadRequest, "unknown_message")
		} else {
			apiError(w, http.StatusInternalServerError, "storage_error")
		}
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"accepted": true})
}
