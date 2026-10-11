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
	response := requestAPI(a, "POST", "/push", "", map[string]string{
		"device_key": device.PushKey, "body": "rejected", "sound": strings.Repeat("x", 129),
	})
	if response.Code != 400 {
		t.Fatal("unbounded sound accepted")
	}
}
