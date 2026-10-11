package httpapi

import (
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestAdminCleanupAuthorizationAndLifecycle(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "cleanup-admin.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	device, err := a.createDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	admin := strings.Repeat("a", 64)
	handler := a.WithWebAdmin(admin).Handler()
	call := func(path, token, payload string, expected int) {
		t.Helper()
		request := httptest.NewRequest("POST", path, strings.NewReader(payload))
		request.Header.Set("Authorization", "Bearer "+token)
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != expected {
			t.Fatalf("%s: %d %s", path, response.Code, response.Body.String())
		}
	}
	payload := `{"device_id":"` + device.DeviceID + `"}`
	call("/api/admin/messages/clear", device.ReceiveToken, `{}`, 401)
	call("/api/admin/devices/delete", device.ReceiveToken, payload, 401)
	call("/api/admin/devices/delete", admin, payload, 400)
	call("/api/admin/test", admin, `{"device_id":"`+device.DeviceID+`","body":"pending"}`, 201)
	call("/api/admin/messages/clear", admin, `{}`, 200)
	call("/api/admin/revoke", admin, payload, 200)
	call("/api/admin/devices/delete", admin, payload, 200)
	call("/api/admin/devices/delete", admin, payload, 400)
}
