package httpapi

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestBarkPresentationPersistsChannelPriority(t *testing.T) {
	a, device, _, _ := setupAPI(t)
	for _, test := range []struct {
		level, sound string
		want         int
	}{
		{"", "", 5}, {"active", "minuet", 5}, {"passive", "", 2},
		{"timeSensitive", "", 8}, {"critical", "", 8}, {"critical", "none", 2},
		// Unknown levels degrade to the default instead of failing the push.
		{"bogus", "", 5}, {"bogus", "none", 2},
		// An over-long sound is ignored while the level still applies.
		{"critical", strings.Repeat("x", 129), 8},
		{"", strings.Repeat("x", 129), 5},
	} {
		response := requestAPI(a, "POST", "/push", "", map[string]string{
			"device_key": device.PushKey, "body": "presentation", "level": test.level, "sound": test.sound,
		})
		var result struct{ ID string }
		if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &result) != nil {
			t.Fatalf("push failed: %d %s", response.Code, response.Body.String())
		}
		var priority int
		if err := a.db.QueryRow("SELECT priority FROM messages WHERE id=?", result.ID).Scan(&priority); err != nil || priority != test.want {
			t.Fatalf("priority=%d want=%d err=%v", priority, test.want, err)
		}
	}
	// The over-long sound is dropped from the extension but never fails the push.
	response := requestAPI(a, "POST", "/push", "", map[string]string{
		"device_key": device.PushKey, "body": "bounded", "sound": strings.Repeat("x", 129),
	})
	message := storedBark(t, a, response)
	var raw string
	if err := a.db.QueryRow("SELECT bark FROM messages WHERE id=?", message.ID).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(raw, "sound") {
		t.Fatalf("unbounded sound stored: %s", raw)
	}
}
