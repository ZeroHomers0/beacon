package httpapi

import (
	"encoding/json"
	"net/http"
	"strconv"

	"beacon.local/server/internal/config"
	"beacon.local/server/internal/domain"
)

func HealthHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", http.MethodGet)
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	// `protocol` stays a JSON string here for backward compatibility with probes
	// written against earlier releases; every other endpoint advertises an
	// integer. Both carry the same contract number.
	_ = json.NewEncoder(w).Encode(map[string]string{
		"status":   "ok",
		"version":  config.Version,
		"protocol": strconv.Itoa(domain.ProtocolVersion),
	})
}
