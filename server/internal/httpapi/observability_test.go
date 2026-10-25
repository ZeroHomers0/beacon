package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestOperationalMetricsAndReadinessAreProtected(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "observation.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	hub := NewStreamHub(a.service)
	defer hub.Close()
	credential := strings.Repeat("a", 64)
	handler := a.WithWebAdmin(credential).WithStream(hub).Handler()
	request := func(path, token string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodGet, path, nil)
		r.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		return w
	}
	for _, path := range []string{"/api/admin/metrics", "/api/admin/ready"} {
		if w := request(path, "bad"); w.Code != 401 {
			t.Fatalf("unauthorized %s: %d", path, w.Code)
		}
	}
	if w := request("/api/admin/ready", credential); w.Code != 200 {
		t.Fatalf("ready: %d", w.Code)
	}
	device, err := a.createDevice("observation")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = a.service.Push(device.DeviceID, "title", "", "body"); err != nil {
		t.Fatal(err)
	}
	w := request("/api/admin/metrics", credential)
	if w.Code != 200 {
		t.Fatalf("metrics: %d %s", w.Code, w.Body.String())
	}
	var payload struct {
		Delivery struct {
			Queued    int   `json:"queued_realtime_messages"`
			SampledAt int64 `json:"sampled_at_ms"`
		} `json:"delivery"`
		Active int `json:"active_connections"`
	}
	if err = json.Unmarshal(w.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload.Delivery.Queued != 1 || payload.Delivery.SampledAt <= 0 || payload.Active != 0 {
		t.Fatalf("snapshot: %+v", payload)
	}
	if strings.Contains(w.Body.String(), device.DeviceID) {
		t.Fatal("device identity leaked")
	}
	hub.Close()
	if w = request("/api/admin/ready", credential); w.Code != 503 {
		t.Fatalf("closed ready: %d", w.Code)
	}
	if w = request("/healthz", ""); w.Code != 200 {
		t.Fatalf("liveness must remain separate: %d", w.Code)
	}
}
