package httpapi

import (
	"encoding/json"
	"net/http"

	"beacon.local/server/internal/config"
)

func HealthHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", http.MethodGet)
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok", "version": config.Version, "protocol": "1"})
}
