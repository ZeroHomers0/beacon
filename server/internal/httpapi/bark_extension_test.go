package httpapi

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
)

func storedExtension(t *testing.T, a *api, id string) map[string]string {
	t.Helper()
	var raw string
	if err := a.db.QueryRow("SELECT bark FROM messages WHERE id=?", id).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	if raw == "" {
		return nil
	}
	var values map[string]string
	if err := json.Unmarshal([]byte(raw), &values); err != nil {
		t.Fatalf("extension is not JSON: %s", raw)
	}
	return values
}

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

// A malformed extension field is dropped on its own: the message still stores
// with 200, and only the offending field is missing from the raw bark column.
func TestBarkMalformedExtensionsAreDroppedNotRejected(t *testing.T) {
	a, device, _, _ := setupAPI(t)
	for _, test := range []struct {
		name   string
		fields map[string]string
		drops  []string
	}{
		{"http image", map[string]string{"body": "x", "image": "http://example.com/a"}, []string{"image"}},
		{"userinfo icon", map[string]string{"body": "x", "icon": "https://user:pass@example.com/a"}, []string{"icon"}},
		{"oversized url", map[string]string{"body": "x", "url": "https://example.com/" + strings.Repeat("a", 2048)}, []string{"url"}},
		{"oversized sound", map[string]string{"body": "x", "sound": strings.Repeat("x", 129)}, []string{"sound"}},
		{"invalid ciphertext", map[string]string{"body": "x", "ciphertext": "invalid"}, []string{"ciphertext", "iv"}},
		{"short iv", map[string]string{"body": "x", "ciphertext": base64.StdEncoding.EncodeToString(make([]byte, 32)), "iv": "short"}, []string{"ciphertext", "iv"}},
	} {
		fields := map[string]string{"device_key": device.PushKey}
		for key, value := range test.fields {
			fields[key] = value
		}
		response := requestAPI(a, "POST", "/push", "", fields)
		message := storedBark(t, a, response)
		extension := storedExtension(t, a, message.ID)
		for _, drop := range test.drops {
			if _, exists := extension[drop]; exists {
				t.Fatalf("%s: %s survived in %v", test.name, drop, extension)
			}
		}
		// Only clean presentation fields survive; the plaintext body always does.
		if message.Body == "" {
			t.Fatalf("%s: message body lost", test.name)
		}
	}
}

func TestBarkEncryptedPresentation(t *testing.T) {
	a, device, _, _ := setupAPI(t)
	encrypted := base64.StdEncoding.EncodeToString(make([]byte, 32))
	response := requestAPI(a, "POST", "/push", "", map[string]string{"device_key": device.PushKey, "ciphertext": encrypted, "iv": "1234567890123456"})
	message := storedBark(t, a, response)
	if message.Body != "加密消息" {
		t.Fatal("ciphertext body leaked")
	}
	extension := storedExtension(t, a, message.ID)
	if extension["ciphertext"] != encrypted || extension["iv"] != "1234567890123456" {
		t.Fatalf("ciphertext not stored: %v", extension)
	}
}
