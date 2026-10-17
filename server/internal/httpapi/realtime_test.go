package httpapi

import (
	"encoding/json"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"beacon.local/server/internal/domain"
	"beacon.local/server/internal/service"
	"beacon.local/server/internal/store"
)

func TestRealtimeConfigurationIsDeviceBoundAndDoesNotExposeSecrets(t *testing.T) {
	repository, err := store.Open(filepath.Join(t.TempDir(), "configuration.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()
	app := service.New(repository)
	device, err := app.CreateDevice("one")
	if err != nil {
		t.Fatal(err)
	}
	other, err := app.CreateDevice("other")
	if err != nil {
		t.Fatal(err)
	}
	request := func(app *service.Service, method, path, token string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, nil)
		r.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		New(app).Handler().ServeHTTP(w, r)
		return w
	}
	if w := request(app, "GET", "/api/v1/realtime/config", device.ReceiveToken); w.Code != 503 {
		t.Fatal("disabled configuration exposed")
	}
	configured := app.WithWebSocket()
	for _, token := range []string{"", device.PushKey, "wrong"} {
		if w := request(configured, "GET", "/api/v1/realtime/config", token); w.Code != 401 {
			t.Fatal("configuration credential privilege escalation")
		}
	}
	if w := request(configured, "GET", "/api/v1/realtime/config?device_id="+other.DeviceID, device.ReceiveToken); w.Code != 400 {
		t.Fatal("query identity accepted")
	}
	if w := request(configured, "POST", "/api/v1/realtime/config", device.ReceiveToken); w.Code != 405 {
		t.Fatal("write accepted")
	}
	w := request(configured, "GET", "/api/v1/realtime/config", device.ReceiveToken)
	if w.Code != 200 || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("configuration failed or cacheable")
	}
	var reply domain.RealtimeConfiguration
	if err := json.Unmarshal(w.Body.Bytes(), &reply); err != nil {
		t.Fatal(err)
	}
	if reply.Protocol != 2 || reply.StreamPath != "/api/v1/realtime/stream" || reply.DeviceID != device.DeviceID || reply.ServerID != app.ServerID() {
		t.Fatal("device configuration mismatch")
	}
	for _, secret := range []string{device.ReceiveToken, device.PushKey, other.ReceiveToken, other.PushKey} {
		if strings.Contains(w.Body.String(), secret) {
			t.Fatal("plaintext credential exposed")
		}
	}
	rotated, err := app.RotateDevice(device.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	if w := request(configured, "GET", "/api/v1/realtime/config", rotated.ReceiveToken); w.Code != 200 {
		t.Fatal("rotated stream configuration unavailable")
	}
	if w := request(configured, "GET", "/api/v1/realtime/config", device.ReceiveToken); w.Code != 401 {
		t.Fatal("old token obtained new generation")
	}
	if w := request(app, "GET", "/api/v1/realtime/config", other.ReceiveToken); w.Code != 503 {
		t.Fatal("configuration mutated original service")
	}
}
