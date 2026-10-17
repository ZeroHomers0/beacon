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

func TestAdminApplicationDeletionRequiresRevocationAndRemovesItsMessages(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "application-delete.db"))
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
	call := func(path, token, payload string, expected int) string {
		t.Helper()
		request := httptest.NewRequest("POST", path, strings.NewReader(payload))
		request.Header.Set("Authorization", "Bearer "+token)
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != expected {
			t.Fatalf("%s: %d %s", path, response.Code, response.Body.String())
		}
		return response.Body.String()
	}
	first, err := a.service.CreateApplication(device.DeviceID, "NAS", 8)
	if err != nil {
		t.Fatal(err)
	}
	second, err := a.service.CreateApplication(device.DeviceID, "CI", 5)
	if err != nil {
		t.Fatal(err)
	}
	// A device-key push stores no application, so it must survive both deletions.
	if _, err = a.service.PushPriority(device.DeviceID, "manual", "", "body", nil); err != nil {
		t.Fatal(err)
	}
	if _, err = a.service.PushCredential(first.Token, "one", "", "body", nil); err != nil {
		t.Fatal(err)
	}
	if _, err = a.service.PushCredential(second.Token, "two", "", "body", nil); err != nil {
		t.Fatal(err)
	}
	firstPayload := `{"id":"` + first.ID + `"}`
	call("/api/admin/applications/delete", device.ReceiveToken, firstPayload, 401)
	// An active application cannot be deleted; revoke first.
	call("/api/admin/applications/delete", admin, firstPayload, 400)
	call("/api/admin/applications/revoke", admin, firstPayload, 200)
	body := call("/api/admin/applications/delete", admin, firstPayload, 200)
	if !strings.Contains(body, `"application_deleted":true`) || !strings.Contains(body, `"deleted":1`) {
		t.Fatalf("unexpected delete response %s", body)
	}
	// Deleting twice, or deleting an unknown id, is rejected rather than silently accepted.
	call("/api/admin/applications/delete", admin, firstPayload, 400)
	call("/api/admin/applications/delete", admin, `{"id":"`+strings.Repeat("b", 64)+`"}`, 400)
	// Only the deleted application's message is gone; the other sender and the manual push stay.
	page, err := a.service.Messages(device.DeviceID, 0, 10, false)
	if err != nil || len(page.Messages) != 2 {
		t.Fatalf("remaining messages: %d %v", len(page.Messages), err)
	}
	for _, message := range page.Messages {
		if message.ApplicationID == first.ID {
			t.Fatal("deleted application message survived")
		}
	}
	// The removed credential no longer authenticates, while the surviving one still does.
	if _, err = a.service.PushCredential(first.Token, "gone", "", "body", nil); err == nil {
		t.Fatal("deleted application credential accepted")
	}
	if _, err = a.service.PushCredential(second.Token, "still", "", "body", nil); err != nil {
		t.Fatal("sibling application credential rejected", err)
	}
}
