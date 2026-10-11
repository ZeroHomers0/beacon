package httpapi

import (
	"beacon.local/server/internal/config"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealthContract(t *testing.T) {
	recorder := httptest.NewRecorder()
	HealthHandler(recorder, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if recorder.Code != http.StatusOK || recorder.Header().Get("Content-Type") != "application/json; charset=utf-8" {
		t.Fatalf("unexpected HTTP response: %d %v", recorder.Code, recorder.Header())
	}
	var response map[string]string
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil || response["status"] != "ok" || response["version"] != config.Version || response["protocol"] != "1" {
		t.Fatalf("invalid health response: %s", recorder.Body.String())
	}
}

func TestHealthRejectsWrites(t *testing.T) {
	recorder := httptest.NewRecorder()
	HealthHandler(recorder, httptest.NewRequest(http.MethodPost, "/healthz", nil))
	if recorder.Code != http.StatusMethodNotAllowed || recorder.Header().Get("Allow") != http.MethodGet {
		t.Fatalf("write was not rejected: %d", recorder.Code)
	}
}
