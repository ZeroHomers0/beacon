// Package httpapi translates HTTP requests into application operations.
package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
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
}

func New(service *service.Service) *API { return &API{service: service} }

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
	d := json.NewDecoder(r.Body)
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
	router.Use(func(c *gin.Context) {
		defer func() {
			if recover() != nil {
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
		"max_message_actions":         3,
		"push_idempotency":            true,
		"max_ttl_seconds":             604800,
		"max_page_size":               domain.MaxPageSize,
		"binding":                     "one_time_code",
		"self_revoke":                 true,
		"delivery_window_ms":          domain.PendingLifetime.Milliseconds(),
		"delivery_retention_protocol": 1,
		"receipt_status":              true,
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
	var payload struct {
		Title    string                `json:"title"`
		Body     string                `json:"body"`
		Subtitle string                `json:"subtitle"`
		Priority *int                  `json:"priority"`
		Group    string                `json:"group"`
		TTL      int64                 `json:"ttl"`
		Actions  domain.MessageActions `json:"actions"`
	}
	if decodeJSON(w, r, &payload) != nil {
		apiError(w, http.StatusBadRequest, "invalid_message")
		return
	}
	m, err := a.service.PushWithOptions(token, payload.Title, payload.Subtitle, payload.Body, payload.Priority, domain.PushOptions{Key: r.Header.Get("Idempotency-Key"), Group: payload.Group, TTL: payload.TTL, Actions: payload.Actions})
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
