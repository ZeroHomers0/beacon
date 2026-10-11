package httpapi

import (
	"beacon.local/server/internal/domain"
	"encoding/hex"
	"net/http"
)

func (a *API) receiptStatus(w http.ResponseWriter, r *http.Request) {
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
		apiError(w, 400, "invalid_receipt_query")
		return
	}
	for _, id := range payload.IDs {
		if _, err := hex.DecodeString(id); len(id) != domain.TokenLength || err != nil {
			apiError(w, 400, "invalid_receipt_query")
			return
		}
	}
	statuses, err := a.service.ReceiptStatuses(device, payload.IDs)
	if err != nil {
		apiError(w, 500, "storage_error")
		return
	}
	writeJSON(w, 200, map[string]any{"statuses": statuses})
}
