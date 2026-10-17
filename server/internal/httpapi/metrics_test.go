package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

// The metrics label must be the route template, never the URL: Bark paths embed the device
// credential, so a path-derived label would pour tokens into an admin response.
func TestMetricsLabelsUseRouteTemplatesNotURLs(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "metrics.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	token := strings.Repeat("b", 64)
	device, err := a.createDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	handler := a.WithWebAdmin(token).Handler()

	for _, path := range []string{"/healthz", "/api/v1/capabilities", "/" + device.ReceiveToken + "/hi"} {
		request := httptest.NewRequest(http.MethodGet, path, nil)
		request.Header.Set("Authorization", "Bearer "+device.ReceiveToken)
		handler.ServeHTTP(httptest.NewRecorder(), request)
	}

	request := httptest.NewRequest(http.MethodGet, "/api/admin/metrics", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("admin metrics: got %d: %s", response.Code, response.Body.String())
	}
	body := response.Body.String()
	if strings.Contains(body, device.ReceiveToken) {
		t.Fatal("metrics response echoed a device credential")
	}
	var payload struct {
		Requests uint64                    `json:"requests"`
		Routes   map[string]map[string]any `json:"routes"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode metrics: %v", err)
	}
	if payload.Requests < 3 {
		t.Fatalf("recorded %d requests, want at least 3", payload.Requests)
	}
	if _, ok := payload.Routes["/healthz"]; !ok {
		t.Fatalf("healthz route missing from %v", payload.Routes)
	}
	// Every Bark path goes through NoRoute, so it collapses into a single bucket.
	if _, ok := payload.Routes["unmatched"]; !ok {
		t.Fatalf("Bark paths were not folded into 'unmatched': %v", payload.Routes)
	}
	for name := range payload.Routes {
		if strings.Contains(name, "secret") || strings.HasPrefix(name, "/"+device.ReceiveToken[:4]) {
			t.Fatalf("route label %q looks like a request path", name)
		}
	}
}

func TestRequestMetricsBucketsLatencyAndServerErrors(t *testing.T) {
	metrics := newRequestMetrics()
	metrics.observe("/healthz", http.StatusOK, 50*time.Millisecond)
	metrics.observe("/healthz", http.StatusOK, 150*time.Millisecond)
	metrics.observe("/healthz", http.StatusOK, 3*time.Second)
	metrics.observe("/api/v1/push", http.StatusInternalServerError, 10*time.Millisecond)

	snapshot := metrics.snapshot()
	if snapshot["requests"] != uint64(4) {
		t.Fatalf("requests = %v, want 4", snapshot["requests"])
	}
	// Only 5xx counts as a failure: a 4xx is a client mistake, not a sick server.
	if snapshot["server_errors"] != uint64(1) {
		t.Fatalf("server_errors = %v, want 1", snapshot["server_errors"])
	}
	health, ok := snapshot["routes"].(map[string]any)["/healthz"].(gin.H)
	if !ok {
		t.Fatalf("healthz entry has type %T", snapshot["routes"])
	}
	// Buckets are exclusive: 50ms, then 150ms, then the 3s outlier lands in neither.
	if health["requests"] != uint64(3) || health["under_100ms"] != uint64(1) || health["under_1s"] != uint64(1) {
		t.Fatalf("healthz histogram = %v", health)
	}
	if health["slowest_ms"] != float64(3000) {
		t.Fatalf("slowest_ms = %v, want 3000", health["slowest_ms"])
	}
}

// A map keyed by anything the client controls is a memory leak. The per-route cap folds the
// overflow into one bucket so the response size stays bounded.
func TestRequestMetricsFoldsUnknownRoutesIntoOneBucket(t *testing.T) {
	metrics := newRequestMetrics()
	for i := 0; i < maxTrackedRoutes*2; i++ {
		metrics.observe("route-"+time.Duration(i).String(), http.StatusOK, time.Millisecond)
	}
	routes := metrics.snapshot()["routes"].(map[string]any)
	if len(routes) > maxTrackedRoutes {
		t.Fatalf("tracked %d routes, cap is %d", len(routes), maxTrackedRoutes)
	}
	overflow, ok := routes["other"].(gin.H)
	if !ok {
		t.Fatal("overflow routes were dropped instead of folded into 'other'")
	}
	if overflow["requests"] == uint64(0) {
		t.Fatal("overflow bucket is empty")
	}
}
