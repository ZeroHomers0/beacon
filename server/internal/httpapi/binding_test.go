package httpapi

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"beacon.local/server/internal/auth"
	"beacon.local/server/internal/domain"
)

func bindingRequest(handler http.Handler, code, name string) *httptest.ResponseRecorder {
	body, _ := json.Marshal(map[string]string{"binding_code": code, "device_name": name})
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodPost, "/api/v1/bind", bytes.NewReader(body)))
	return response
}

func TestBindingCreatesIsolatedCredentialsAndConsumesCode(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "beacon.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	code, err := a.service.CreateBindingCode("phone", 10*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	response := bindingRequest(a.handler(), strings.ToUpper(code.Code), "new name")
	if response.Code != http.StatusCreated || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("bind status %d", response.Code)
	}
	var credentials domain.DeviceCredentials
	if err = json.Unmarshal(response.Body.Bytes(), &credentials); err != nil {
		t.Fatal(err)
	}
	if credentials.ServerID != a.serverID || credentials.PushKey == credentials.ReceiveToken {
		t.Fatal("invalid identity")
	}
	for _, push := range []bool{false, true} {
		token := credentials.ReceiveToken
		if push {
			token = credentials.PushKey
		}
		device, err := a.authenticateToken(token, push)
		if err != nil || device != credentials.DeviceID {
			t.Fatal("credential not usable")
		}
		if _, err = a.authenticateToken(token, !push); err == nil {
			t.Fatal("credential roles are not isolated")
		}
	}
	if bindingRequest(a.handler(), code.Code, "phone").Code != http.StatusBadRequest {
		t.Fatal("code was reusable")
	}
	var name, codeHash, receiveHash, pushHash string
	if err = a.db.QueryRow("SELECT name,receive_hash,push_hash FROM devices WHERE id=?", credentials.DeviceID).Scan(&name, &receiveHash, &pushHash); err != nil {
		t.Fatal(err)
	}
	if err = a.db.QueryRow("SELECT code_hash FROM binding_codes").Scan(&codeHash); err != nil {
		t.Fatal(err)
	}
	if name != "new name" || codeHash != auth.HashToken(code.Code) || receiveHash != auth.HashToken(credentials.ReceiveToken) || pushHash != auth.HashToken(credentials.PushKey) {
		t.Fatal("unexpected persisted credentials")
	}
}

func TestExpiredAndUnknownBindingLeaveNoDevice(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "beacon.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	code, err := a.service.CreateBindingCode("phone", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = a.db.Exec("UPDATE binding_codes SET expires_at=0"); err != nil {
		t.Fatal(err)
	}
	for _, invalid := range []string{code.Code, strings.Repeat("a", 32), "short", strings.Repeat("z", 32)} {
		if bindingRequest(a.handler(), invalid, "phone").Code != http.StatusBadRequest {
			t.Fatal("invalid code accepted")
		}
	}
	var count int
	if err = a.db.QueryRow("SELECT count(*) FROM devices").Scan(&count); err != nil || count != 0 {
		t.Fatal("failed binding created a device")
	}
}

func TestConcurrentRedemptionHasOneWinner(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "beacon.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	code, err := a.service.CreateBindingCode("phone", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	statuses := make(chan int, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); statuses <- bindingRequest(a.handler(), code.Code, "").Code }()
	}
	wg.Wait()
	close(statuses)
	winners := 0
	for status := range statuses {
		if status == http.StatusCreated {
			winners++
		} else if status != http.StatusBadRequest {
			t.Fatalf("unexpected status %d", status)
		}
	}
	if winners != 1 {
		t.Fatalf("winners=%d", winners)
	}
}

func TestDeviceRevocationPersistsAndPreservesMessages(t *testing.T) {
	path := filepath.Join(t.TempDir(), "beacon.db")
	a, err := openAPI(path)
	if err != nil {
		t.Fatal(err)
	}
	credentials, err := a.createDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	other, err := a.createDevice("other")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = a.service.Push(credentials.DeviceID, "title", "", "body"); err != nil {
		t.Fatal(err)
	}
	if err = a.service.RevokeDevice(credentials.DeviceID); err != nil {
		t.Fatal(err)
	}
	a.close()
	a, err = openAPI(path)
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	for _, endpoint := range []string{"/api/v1/capabilities", "/api/v1/messages"} {
		r := httptest.NewRequest(http.MethodGet, endpoint, nil)
		r.Header.Set("Authorization", "Bearer "+credentials.ReceiveToken)
		w := httptest.NewRecorder()
		a.handler().ServeHTTP(w, r)
		if w.Code != http.StatusUnauthorized {
			t.Fatal("revoked receive credential accepted")
		}
	}
	if _, err = a.authenticateToken(credentials.PushKey, true); err == nil {
		t.Fatal("revoked push key accepted")
	}
	if _, err = a.authenticateToken(other.ReceiveToken, false); err != nil {
		t.Fatal("other device affected")
	}
	devices, err := a.service.ListDevices()
	if err != nil || len(devices) != 2 {
		t.Fatal("cannot list devices")
	}
	for _, device := range devices {
		if device.DeviceID == credentials.DeviceID && !device.Revoked {
			t.Fatal("revocation status missing")
		}
	}
	if w := requestAPI(a, "POST", "/api/v1/messages/ack", credentials.ReceiveToken, map[string]any{"ids": []string{strings.Repeat("a", 64)}}); w.Code != http.StatusUnauthorized {
		t.Fatal("revoked ACK accepted")
	}
	if w := requestAPI(a, "POST", "/api/v1/push", credentials.PushKey, map[string]string{"body": "blocked"}); w.Code != http.StatusUnauthorized {
		t.Fatal("revoked push accepted")
	}
	var count int
	if err = a.db.QueryRow("SELECT count(*) FROM messages WHERE device=?", credentials.DeviceID).Scan(&count); err != nil || count != 1 {
		t.Fatal("history deleted")
	}
}

func TestBindingRateLimitAndStrictPayload(t *testing.T) {
	a, err := openAPI(filepath.Join(t.TempDir(), "beacon.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	r := httptest.NewRequest(http.MethodPost, "/api/v1/bind", strings.NewReader(`{"binding_code":"x","extra":true}`))
	w := httptest.NewRecorder()
	a.handler().ServeHTTP(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatal("unknown payload accepted")
	}
	a.bindingLimit.mu.Lock()
	a.bindingLimit.tokens = 0
	a.bindingLimit.next = time.Now()
	a.bindingLimit.mu.Unlock()
	w = bindingRequest(a.handler(), strings.Repeat("a", 32), "")
	if w.Code != http.StatusTooManyRequests || w.Header().Get("Retry-After") == "" {
		t.Fatal("limit missing")
	}
}
