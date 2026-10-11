package httpapi

import (
	"errors"
	"net/http"
	"strings"

	"beacon.local/server/internal/domain"
)

func (a *API) realtimeConfiguration(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodGet) {
		return
	}
	device, err := a.authenticate(r, false)
	if err != nil {
		apiError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if r.URL.RawQuery != "" || r.URL.ForceQuery {
		apiError(w, http.StatusBadRequest, "invalid_query")
		return
	}
	configuration, err := a.service.RealtimeConfiguration(device, strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
	if errors.Is(err, domain.ErrUnauthorized) {
		apiError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if errors.Is(err, domain.ErrRealtimeUnavailable) {
		apiError(w, http.StatusServiceUnavailable, "realtime_unavailable")
		return
	}
	if err != nil {
		apiError(w, http.StatusInternalServerError, "storage_error")
		return
	}
	writeJSON(w, http.StatusOK, configuration)
}
