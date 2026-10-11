package httpapi

import (
	"errors"
	"mime"
	"net/http"
	"strings"

	"beacon.local/server/internal/domain"
)

const maxRevocationRequestBytes = 1024

func (a *API) revokeSelf(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, http.MethodPost) {
		return
	}
	mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/json" {
		apiError(w, http.StatusUnsupportedMediaType, "json_required")
		return
	}
	var payload struct {
		ServerID string `json:"server_id"`
		DeviceID string `json:"device_id"`
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxRevocationRequestBytes)
	if decodeJSON(w, r, &payload) != nil {
		apiError(w, http.StatusBadRequest, "invalid_device")
		return
	}
	header := r.Header.Get("Authorization")
	if !strings.HasPrefix(header, "Bearer ") {
		apiError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if err = a.service.RevokeSelf(strings.TrimPrefix(header, "Bearer "), payload.ServerID, payload.DeviceID); err != nil {
		if errors.Is(err, domain.ErrUnauthorized) {
			apiError(w, http.StatusUnauthorized, "unauthorized")
		} else {
			apiError(w, http.StatusInternalServerError, "storage_error")
		}
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"revoked": true, "server_id": payload.ServerID, "device_id": payload.DeviceID})
}
