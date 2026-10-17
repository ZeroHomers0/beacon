package httpapi

import (
	"errors"
	"net/http"
	"sync"
	"time"

	"beacon.local/server/internal/domain"
)

// Bounded global admission: independent of spoofable proxy headers, no unbounded IP map.
type bindingLimiter struct {
	mu     sync.Mutex
	next   time.Time
	tokens float64
}

func (l *bindingLimiter) allow() bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	if l.next.IsZero() {
		l.tokens = 20
	} else {
		l.tokens = min(20, l.tokens+now.Sub(l.next).Seconds())
	}
	l.next = now
	if l.tokens < 1 {
		return false
	}
	l.tokens--
	return true
}

func (a *API) bind(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodPost) {
		return
	}
	if !a.bindingLimit.allow() {
		w.Header().Set("Retry-After", "1")
		apiError(w, http.StatusTooManyRequests, "binding_rate_limited")
		return
	}
	var request struct {
		Code string `json:"binding_code"`
		Name string `json:"device_name"`
	}
	if decodeJSON(w, r, &request) != nil {
		apiError(w, http.StatusBadRequest, "invalid_binding_request")
		return
	}
	credentials, err := a.service.Bind(request.Code, request.Name)
	if err != nil {
		if errors.Is(err, domain.ErrStorageCapacity) {
			apiError(w, http.StatusInsufficientStorage, "storage_capacity_reached")
		} else if errors.Is(err, domain.ErrInvalidBinding) {
			apiError(w, http.StatusBadRequest, "invalid_or_expired_binding")
		} else {
			apiError(w, http.StatusInternalServerError, "storage_error")
		}
		return
	}
	writeJSON(w, http.StatusCreated, struct {
		domain.DeviceCredentials
		Protocol int `json:"protocol"`
	}{credentials, domain.ProtocolVersion})
}
