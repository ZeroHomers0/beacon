package httpapi

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func requestAPI(a *api, method, path, token string, body any) *httptest.ResponseRecorder {
	var data bytes.Buffer
	if body != nil {
		_ = json.NewEncoder(&data).Encode(body)
	}
	r := httptest.NewRequest(method, path, &data)
	if body != nil {
		r.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	a.handler().ServeHTTP(w, r)
	return w
}

func setupAPI(t *testing.T) (*api, deviceCredentials, deviceCredentials, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "test.db")
	a, err := openAPI(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { a.close() })
	one, err := a.createDevice("one")
	if err != nil {
		t.Fatal(err)
	}
	two, err := a.createDevice("two")
	if err != nil {
		t.Fatal(err)
	}
	return a, one, two, path
}

func pushTest(t *testing.T, a *api, c deviceCredentials) wireMessage {
	t.Helper()
	w := requestAPI(a, "POST", "/api/v1/push", c.PushKey, map[string]string{"title": "标题", "body": "内容"})
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var m wireMessage
	if json.Unmarshal(w.Body.Bytes(), &m) != nil {
		t.Fatal("decode")
	}
	return m
}

func TestPermissionsAndDeviceIsolation(t *testing.T) {
	a, one, two, _ := setupAPI(t)
	m := pushTest(t, a, one)
	for _, test := range []struct {
		method, path, token string
		body                any
	}{
		{"GET", "/api/v1/messages", one.PushKey, nil}, {"POST", "/api/v1/push", one.ReceiveToken, map[string]string{"body": "x"}}, {"GET", "/api/v1/messages", "", nil},
	} {
		if w := requestAPI(a, test.method, test.path, test.token, test.body); w.Code != 401 {
			t.Fatal("permission leak", w.Code)
		}
	}
	w := requestAPI(a, "GET", "/api/v1/messages", two.ReceiveToken, nil)
	var page struct {
		Messages []wireMessage `json:"messages"`
	}
	_ = json.Unmarshal(w.Body.Bytes(), &page)
	if len(page.Messages) != 0 {
		t.Fatal("cross device read")
	}
	w = requestAPI(a, "POST", "/api/v1/messages/ack", two.ReceiveToken, map[string]any{"ids": []string{m.ID}})
	if w.Code != 400 {
		t.Fatal("cross device ACK")
	}
}

func TestCapabilitiesSeparateTransportAndPushAPI(t *testing.T) {
	a, one, _, _ := setupAPI(t)
	w := requestAPI(a, http.MethodGet, "/api/v1/capabilities", one.ReceiveToken, nil)
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), `"push_apis":["beacon","bark_text"]`) || !strings.Contains(w.Body.String(), `"transports":["http"]`) {
		t.Fatal("capabilities", w.Code, w.Body.String())
	}
}

func TestPaginationAckAndRestart(t *testing.T) {
	a, one, _, path := setupAPI(t)
	first := pushTest(t, a, one)
	pushTest(t, a, one)
	w := requestAPI(a, "GET", "/api/v1/messages?limit=1", one.ReceiveToken, nil)
	var page struct {
		Messages []wireMessage `json:"messages"`
		Next     int64         `json:"next_cursor"`
		More     bool          `json:"has_more"`
	}
	_ = json.Unmarshal(w.Body.Bytes(), &page)
	if len(page.Messages) != 1 || page.Next != first.Sequence || !page.More {
		t.Fatal("pagination", w.Body.String())
	}
	for i := 0; i < 2; i++ {
		if w = requestAPI(a, "POST", "/api/v1/messages/ack", one.ReceiveToken, map[string]any{"ids": []string{first.ID}}); w.Code != 200 {
			t.Fatal("ACK not idempotent")
		}
	}
	if err := a.close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := openAPI(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.close()
	if reopened.serverID != one.ServerID {
		t.Fatal("server identity changed")
	}
	w = requestAPI(reopened, "GET", "/api/v1/messages?pending=1", one.ReceiveToken, nil)
	_ = json.Unmarshal(w.Body.Bytes(), &page)
	if len(page.Messages) != 1 || page.Messages[0].ID == first.ID {
		t.Fatal("ACK persistence")
	}
}

func TestAckBatchRollsBackAndRejectsMalformedRequest(t *testing.T) {
	a, one, two, _ := setupAPI(t)
	m := pushTest(t, a, one)
	other := pushTest(t, a, two)
	w := requestAPI(a, "POST", "/api/v1/messages/ack", one.ReceiveToken, map[string]any{"ids": []string{m.ID, other.ID}})
	if w.Code != 400 {
		t.Fatal("invalid batch accepted")
	}
	var acked int
	if err := a.db.QueryRow("SELECT acked FROM messages WHERE id=?", m.ID).Scan(&acked); err != nil || acked != 0 {
		t.Fatal("partial ACK committed")
	}
	for _, path := range []string{"/api/v1/messages?after=-1", "/api/v1/messages?limit=0", "/api/v1/messages?pending=bad"} {
		if w = requestAPI(a, http.MethodGet, path, one.ReceiveToken, nil); w.Code != 400 {
			t.Fatal("bad query accepted", path)
		}
	}
	if w = requestAPI(a, "POST", "/api/v1/push", one.PushKey, map[string]any{"body": "ok", "unexpected": 1}); w.Code != 400 {
		t.Fatal("unknown fields accepted")
	}
}

func TestBarkJSONAndPathCompatibility(t *testing.T) {
	a, one, _, _ := setupAPI(t)
	for _, path := range []string{"/" + one.PushKey + "/标题/正文", "/" + one.PushKey + "/标题"} {
		w := requestAPI(a, http.MethodGet, path, "", nil)
		if w.Code != http.StatusOK {
			t.Fatal("bark path", w.Code, w.Body.String())
		}
	}
	w := requestAPI(a, http.MethodPost, "/"+one.PushKey, "", map[string]string{"title": "JSON", "body": "内容"})
	if w.Code != http.StatusOK {
		t.Fatal("bark json", w.Code, w.Body.String())
	}
	w = requestAPI(a, http.MethodGet, "/invalid", "", nil)
	if w.Code != http.StatusNotFound {
		t.Fatal("invalid bark path", w.Code)
	}
	form := strings.NewReader("title=Form+Title&body=Form+Body")
	r := httptest.NewRequest(http.MethodPost, "/"+one.PushKey, form)
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	w = httptest.NewRecorder()
	a.handler().ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatal("bark form", w.Code, w.Body.String())
	}
}
