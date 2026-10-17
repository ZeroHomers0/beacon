package httpapi

import (
	"beacon.local/server/internal/domain"
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"reflect"
	"testing"
	"time"
)

func TestMessageActionsRoundtrip(t *testing.T) {
	a, device, _, path := setupAPI(t)
	send := func(actions any) *httptest.ResponseRecorder {
		raw, _ := json.Marshal(map[string]any{"body": "build finished", "actions": actions})
		r := httptest.NewRequest("POST", "/api/v1/push", bytes.NewReader(raw))
		r.Header.Set("Authorization", "Bearer "+device.PushKey)
		r.Header.Set("Idempotency-Key", "actions-roundtrip")
		w := httptest.NewRecorder()
		a.handler().ServeHTTP(w, r)
		return w
	}
	actions := []domain.MessageAction{{Title: "查看日志", URL: "https://example.com/logs?build=42"}}
	first := send(actions)
	if first.Code != 200 {
		t.Fatal(first.Code, first.Body)
	}
	var stored domain.Message
	if err := json.Unmarshal(first.Body.Bytes(), &stored); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(stored.Actions, actions) {
		t.Fatal(stored)
	}
	replay := send(actions)
	if replay.Code != 200 || replay.Body.String() != first.Body.String() {
		t.Fatal(replay.Code, replay.Body)
	}
	if conflict := send([]domain.MessageAction{{Title: "查看结果", URL: actions[0].URL}}); conflict.Code != 409 {
		t.Fatal(conflict.Code, conflict.Body)
	}
	tasks, err := a.repository.ClaimPublish(time.Now().UnixMilli(), 10)
	if err != nil || len(tasks) != 1 || !reflect.DeepEqual(tasks[0].Message.Actions, actions) {
		t.Fatal(tasks, err)
	}
	reopened, err := openAPI(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.close()
	page, err := reopened.repository.Messages(device.DeviceID, 0, 10, false)
	if err != nil || len(page.Messages) != 1 || !reflect.DeepEqual(page.Messages[0].Actions, actions) {
		t.Fatal(page, err)
	}
}
func TestInvalidMessageActions(t *testing.T) {
	a, device, _, _ := setupAPI(t)
	cases := []string{
		"null", "[null]", "[{}]",
		`[{"title":"open","title":"again","url":"https://example.com"}]`,
		`[{"title":"open","url":"https://example.com","url":"https://other.com"}]`,
		`[{"title":null,"url":"https://example.com"}]`,
		`[{"title":"open","url":null}]`, "[{\"title\":\" \",\"url\":\"https://example.com\"}]",
		"[{\"title\":\"open\",\"url\":\"https://example.com\",\"method\":\"POST\"}]",
	}
	for _, url := range []string{"http://example.com", "javascript:alert(1)", "https://user:pass@example.com", "https://example.com:0", "https://example.com:65536", "https://example.com:", "https://-bad.example", "https://[bad]", "https://example.com/a b", "//example.com"} {
		raw, _ := json.Marshal([]domain.MessageAction{{Title: "open", URL: url}})
		cases = append(cases, string(raw))
	}
	for _, actions := range cases {
		r := httptest.NewRequest("POST", "/api/v1/push", bytes.NewBufferString("{\"body\":\"test\",\"actions\":"+actions+"}"))
		r.Header.Set("Authorization", "Bearer "+device.PushKey)
		w := httptest.NewRecorder()
		a.handler().ServeHTTP(w, r)
		if w.Code != 400 {
			t.Fatalf("%s: %d %s", actions, w.Code, w.Body)
		}
	}
	page, err := a.repository.Messages(device.DeviceID, 0, 10, false)
	if err != nil || len(page.Messages) != 0 {
		t.Fatal(page, err)
	}
	for _, actions := range []any{[]domain.MessageAction{}, []domain.MessageAction{{Title: "open", URL: "HTTPS://example.com/log"}}, []domain.MessageAction{{Title: "open", URL: "https://[::1]:443/x"}}} {
		w := requestAPI(a, "POST", "/api/v1/push", device.PushKey, map[string]any{"body": "valid", "actions": actions})
		if w.Code != 200 {
			t.Fatal(w.Code, w.Body)
		}
	}
}
