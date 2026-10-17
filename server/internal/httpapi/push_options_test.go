package httpapi

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestSharedPushContract(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "contract", "push-contract.json"))
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Name    string
		Payload json.RawMessage
		Status  int
		TTL     int64
		Group   string
		Key     string
	}
	if err = json.Unmarshal(raw, &cases); err != nil {
		t.Fatal(err)
	}
	for _, c := range cases {
		t.Run(c.Name, func(t *testing.T) {
			a, device, _, _ := setupAPI(t)
			r := httptest.NewRequest("POST", "/api/v1/push", bytes.NewReader(c.Payload))
			r.Header.Set("Authorization", "Bearer "+device.PushKey)
			r.Header.Set("Idempotency-Key", c.Key)
			w := httptest.NewRecorder()
			a.handler().ServeHTTP(w, r)
			if w.Code != c.Status {
				t.Fatalf("%d: %s", w.Code, w.Body)
			}
			if c.Status == 200 {
				var m wireMessage
				if err = json.Unmarshal(w.Body.Bytes(), &m); err != nil {
					t.Fatal(err)
				}
				if m.ExpiresAt-m.CreatedAt != c.TTL*1000 || m.Group != c.Group {
					t.Fatalf("metadata: %+v", m)
				}
			}
		})
	}
}

func TestPushIdempotencyPersistsAfterAckCleanupAndRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "push.db")
	a, err := openAPI(path)
	if err != nil {
		t.Fatal(err)
	}
	device, err := a.createDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	send := func(body, key, token string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "/api/v1/push", bytes.NewBufferString(body))
		r.Header.Set("Authorization", "Bearer "+token)
		r.Header.Set("Idempotency-Key", key)
		w := httptest.NewRecorder()
		a.handler().ServeHTTP(w, r)
		return w
	}
	body := `{"title":"CI","body":"complete","group":"build","ttl":60}`
	first := send(body, "build-1", device.PushKey)
	if first.Code != 200 {
		t.Fatal(first.Body)
	}
	var m wireMessage
	json.Unmarshal(first.Body.Bytes(), &m)
	for i := 0; i < 210; i++ {
		w := send(body, "build-1", device.PushKey)
		if w.Code != 200 || w.Body.String() != first.Body.String() {
			t.Fatal("replay changed", w.Code, w.Body)
		}
	}
	conflict := send(`{"body":"different"}`, "build-1", device.PushKey)
	if conflict.Code != 409 {
		t.Fatal("conflict", conflict.Code)
	}
	if err = a.repository.Ack(device.DeviceID, []string{m.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err = a.repository.CleanupConfirmed(m.CreatedAt+1, true); err != nil {
		t.Fatal(err)
	}
	a.close()
	a, err = openAPI(path)
	if err != nil {
		t.Fatal(err)
	}
	defer a.close()
	replay := send(body, "build-1", device.PushKey)
	if replay.Code != 200 || replay.Body.String() != first.Body.String() {
		t.Fatal("lost durable result", replay.Code, replay.Body)
	}
	app, err := a.service.CreateApplication(device.DeviceID, "CI", 5)
	if err != nil {
		t.Fatal(err)
	}
	independent := send(body, "build-1", app.Token)
	if independent.Code != 200 || independent.Body.String() == first.Body.String() {
		t.Fatal("credential scope", independent.Code, independent.Body)
	}
	if err = a.service.RevokeDevice(device.DeviceID); err != nil {
		t.Fatal(err)
	}
	if revoked := send(body, "build-1", device.PushKey); revoked.Code != 401 {
		t.Fatal("revoked replay", revoked.Code)
	}
}
