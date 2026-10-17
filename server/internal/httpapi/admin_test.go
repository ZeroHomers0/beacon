package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestAdminCredentialIsolationAndBinding(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "web.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	token := strings.Repeat("a", 64)
	device, err := a.createDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	handler := a.WithWebAdmin(token).Handler()
	for _, credential := range []string{"", device.ReceiveToken, device.PushKey, token} {
		request := httptest.NewRequest(http.MethodGet, "/api/admin/overview", nil)
		request.Header.Set("Authorization", "Bearer "+credential)
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		expected := 401
		if credential == token {
			expected = 200
		}
		if response.Code != expected {
			t.Fatalf("got %d want %d", response.Code, expected)
		}
	}
	request := httptest.NewRequest(http.MethodPost, "/api/admin/binding", strings.NewReader(`{"name":"web phone"}`))
	request.Header.Set("Authorization", "Bearer "+token)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != 201 || !strings.Contains(response.Body.String(), "binding_code") {
		t.Fatal(response.Body.String())
	}
	response = httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest("GET", "/admin", nil))
	if response.Code != http.StatusNotFound {
		t.Fatal("retired admin page must return 404")
	}

	testReq := httptest.NewRequest(http.MethodPost, "/api/admin/test", strings.NewReader(`{"device_id":"`+device.DeviceID+`","title":"admin title","subtitle":"admin subtitle","body":"admin body"}`))
	testReq.Header.Set("Authorization", "Bearer "+token)
	testResp := httptest.NewRecorder()
	handler.ServeHTTP(testResp, testReq)
	if testResp.Code != 201 {
		t.Fatalf("admin test message failed: %d %s", testResp.Code, testResp.Body.String())
	}
}

func TestNativeAdminManagementLifecycle(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "native-admin.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	token := strings.Repeat("b", 64)
	handler := a.WithWebAdmin(token).Handler()
	call := func(method, path, credential, body string, expected int) map[string]any {
		t.Helper()
		request := httptest.NewRequest(method, path, strings.NewReader(body))
		request.Header.Set("Authorization", "Bearer "+credential)
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != expected {
			t.Fatalf("%s %s: got %d want %d", method, path, response.Code, expected)
		}
		var result map[string]any
		if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		return result
	}
	for _, path := range []string{"/admin", "/admin/", "/admin/app.js", "/admin/style.css"} {
		call("GET", path, "", "", 404)
	}
	binding := call("POST", "/api/admin/binding", token, `{"name":"native phone"}`, 201)
	bindPayload := `{"binding_code":"` + binding["binding_code"].(string) + `","device_name":"native phone"}`
	device := call("POST", "/api/v1/bind", "", bindPayload, 201)
	call("POST", "/api/v1/bind", "", bindPayload, 400)
	id := device["device_id"].(string)
	app := call("POST", "/api/admin/applications", token, `{"device_id":"`+id+`","name":"NAS","default_priority":8}`, 201)
	appToken := app["token"].(string)
	call("GET", "/api/admin/overview", appToken, "", 401)
	call("GET", "/api/v1/messages", appToken, "", 401)
	call("POST", "/api/v1/push", appToken, `{"title":"NAS","body":"test"}`, 200)
	overview := call("GET", "/api/admin/overview", token, "", 200)
	if overview["version"] == "" || overview["admin_protocol"] != float64(1) {
		t.Fatal("missing native admin compatibility metadata")
	}
	encoded, _ := json.Marshal(overview)
	if strings.Contains(string(encoded), appToken) || strings.Contains(string(encoded), device["receive_token"].(string)) {
		t.Fatal("overview must not expose credentials")
	}
	call("GET", "/api/admin/overview?token=x", token, "", 400)
	call("POST", "/api/admin/applications/revoke", token, `{"id":"`+app["id"].(string)+`"}`, 200)
	call("POST", "/api/v1/push", appToken, `{"title":"NAS","body":"test"}`, 401)
	second := call("POST", "/api/admin/applications", token, `{"device_id":"`+id+`","name":"monitor","default_priority":5}`, 201)
	call("POST", "/api/admin/revoke", token, `{"device_id":"`+id+`"}`, 200)
	call("POST", "/api/v1/push", second["token"].(string), `{"title":"NAS","body":"test"}`, 401)
	call("GET", "/api/v1/messages", device["receive_token"].(string), "", 401)
}

func TestManagementDisabledWithoutDedicatedToken(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "disabled-admin.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	response := httptest.NewRecorder()
	a.Handler().ServeHTTP(response, httptest.NewRequest("GET", "/api/admin/overview", nil))
	if response.Code != 404 {
		t.Fatal("management must be disabled without an administrator token")
	}
}
