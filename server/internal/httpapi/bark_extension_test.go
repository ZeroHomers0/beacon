package httpapi

import (
	"encoding/base64"
	"encoding/json"
	"testing"
)

func TestBarkExtensionsPersistWithoutFetching(t *testing.T) {
	a, device, _, _ := setupAPI(t)
	response := requestAPI(a, "POST", "/push", "", map[string]string{"device_key": device.PushKey, "body": "photo", "image": "https://example.com/a.png", "icon": "https://example.com/icon.png", "url": "https://example.com", "sound": "custom"})
	stored := storedBark(t, a, response)
	var raw string
	if err := a.db.QueryRow("SELECT bark FROM messages WHERE id=?", stored.ID).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	var values map[string]string
	if json.Unmarshal([]byte(raw), &values) != nil || values["image"] != "https://example.com/a.png" || values["sound"] != "custom" {
		t.Fatal("extension lost")
	}
	page, err := a.service.Messages(device.DeviceID, 0, 10, true)
	if err != nil || len(page.Messages) != 1 || page.Messages[0].Bark != raw {
		t.Fatal("HTTP extension lost", err)
	}
}

func TestBarkEncryptedAndMalformedRequests(t *testing.T) {
	a, device, _, _ := setupAPI(t)
	encrypted := base64.StdEncoding.EncodeToString(make([]byte, 32))
	response := requestAPI(a, "POST", "/push", "", map[string]string{"device_key": device.PushKey, "ciphertext": encrypted, "iv": "1234567890123456"})
	if storedBark(t, a, response).Body != "加密消息" {
		t.Fatal("ciphertext body leaked")
	}
	for _, fields := range []map[string]string{
		{"body": "x", "image": "http://example.com/a"},
		{"body": "x", "icon": "https://user:pass@example.com/a"},
		{"ciphertext": "invalid"}, {"ciphertext": encrypted, "iv": "short"},
		{"ciphertext": encrypted, "body": "mixed plaintext"},
	} {
		fields["device_key"] = device.PushKey
		if result := requestAPI(a, "POST", "/push", "", fields); result.Code != 400 {
			t.Fatalf("bad extension accepted: %d", result.Code)
		}
	}
}
