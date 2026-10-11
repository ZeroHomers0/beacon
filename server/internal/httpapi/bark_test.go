package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func storedBark(t *testing.T, a *api, response *httptest.ResponseRecorder) wireMessage {
	t.Helper()
	var result struct {
		Code      int
		Message   string
		Timestamp int64
		ID        string
	}
	if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &result) != nil || result.Code != 200 || result.Timestamp <= 0 {
		t.Fatalf("invalid response: %d %s", response.Code, response.Body.String())
	}
	var m wireMessage
	if err := a.db.QueryRow("SELECT id,seq,title,body,created,subtitle FROM messages WHERE id=?", result.ID).Scan(&m.ID, &m.Sequence, &m.Title, &m.Body, &m.CreatedAt, &m.Subtitle); err != nil {
		t.Fatal(err)
	}
	return m
}

func TestBarkTextRoutesAndEncodedSegments(t *testing.T) {
	a, one, _, _ := setupAPI(t)
	for _, c := range []struct{ path, title, subtitle, body string }{
		{"/正文", "", "", "正文"},
		{"/标题/正文", "标题", "", "正文"},
		{"/标题/副标题/正文", "标题", "副标题", "正文"},
		{"/T%2FA/S%2BB/B%252F%3F%23%0A中文+", "T/A", "S+B", "B%2F?#\n中文+"},
	} {
		m := storedBark(t, a, requestAPI(a, "GET", "/"+one.PushKey+c.path, "", nil))
		if m.Title != c.title || m.Subtitle != c.subtitle || m.Body != c.body {
			t.Fatalf("changed text: %#v", m)
		}
	}
}

func TestBarkPrecedenceAndPushJSON(t *testing.T) {
	a, one, _, _ := setupAPI(t)
	m := storedBark(t, a, requestAPI(a, "POST", "/push", "", map[string]string{"device_key": one.PushKey, "title": "T", "subtitle": "S", "body": "B"}))
	if m.Subtitle != "S" {
		t.Fatal("subtitle lost")
	}
	m = storedBark(t, a, requestAPI(a, "POST", "/"+one.PushKey+"/pathT/pathS/pathB?title=queryT&body=queryB", "", map[string]string{"title": "jsonT", "subtitle": "jsonS", "body": "jsonB"}))
	if m.Title != "pathT" || m.Subtitle != "pathS" || m.Body != "pathB" {
		t.Fatal("path precedence")
	}
	m = storedBark(t, a, requestAPI(a, "POST", "/"+one.PushKey+"?body=query%2Bbody", "", map[string]string{"body": "jsonB"}))
	if m.Body != "query+body" {
		t.Fatal("query precedence")
	}
	r := httptest.NewRequest("POST", "/"+one.PushKey, strings.NewReader(url.Values{"title": {"Form"}, "subtitle": {"中文副标题"}, "body": {"+ / %"}}.Encode()))
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
	w := httptest.NewRecorder()
	a.handler().ServeHTTP(w, r)
	if m = storedBark(t, a, w); m.Subtitle != "中文副标题" || m.Body != "+ / %" {
		t.Fatal("form decoding")
	}
}

func TestBarkInvalidRequestsDoNotStore(t *testing.T) {
	a, one, _, _ := setupAPI(t)
	for _, c := range []struct {
		method, path, media, body string
		status                    int
	}{
		{"PUT", "/" + one.PushKey + "/body", "", "", 405},
		{"DELETE", "/" + one.PushKey + "/body", "", "", 405},
		{"GET", "/push", "", "", 405},
		{"GET", "/" + one.ReceiveToken + "/body", "", "", 401},
		{"GET", "/" + one.PushKey + "/body?group=unsupported", "", "", 400},
		{"GET", "/" + one.PushKey + "?body=a&body=b", "", "", 400},
		{"GET", "/" + one.PushKey + "/%FF", "", "", 400},
		{"POST", "/" + one.PushKey, "text/plain", "body", 415},
		{"POST", "/" + one.PushKey, "application/json", `{"body":null}`, 400},
		{"POST", "/" + one.PushKey, "application/json", `{"body":"a","body":"b"}`, 400},
		{"POST", "/" + one.PushKey, "application/json", `{"body":"a"} {}`, 400},
		{"POST", "/" + one.PushKey, "application/json", `{"body":"a","level":"unknown"}`, 400},
		{"POST", "/" + one.PushKey, "application/json", `{"body":"a","subtitle":"` + strings.Repeat("中", 342) + `"}`, 400},
		{"POST", "/" + one.PushKey, "application/x-www-form-urlencoded", "body=" + strings.Repeat("x", barkRequestLimit), 413},
	} {
		r := httptest.NewRequest(c.method, c.path, strings.NewReader(c.body))
		if c.media != "" {
			r.Header.Set("Content-Type", c.media)
		}
		w := httptest.NewRecorder()
		a.handler().ServeHTTP(w, r)
		var result struct {
			Code      int
			Timestamp int64
		}
		if w.Code != c.status || json.Unmarshal(w.Body.Bytes(), &result) != nil || result.Code != c.status || result.Timestamp <= 0 {
			t.Fatalf("invalid rejection: %d %s", w.Code, w.Body.String())
		}
	}
	var count int
	if err := a.db.QueryRow("SELECT count(*) FROM messages").Scan(&count); err != nil || count != 0 {
		t.Fatal("invalid requests stored messages", count, err)
	}
}

func TestSubtitleSchemaUpgradeAndReopen(t *testing.T) {
	a, one, _, path := setupAPI(t)
	old := pushTest(t, a, one)
	if w := requestAPI(a, "POST", "/api/v1/messages/ack", one.ReceiveToken, map[string]any{"ids": []string{old.ID}}); w.Code != 200 {
		t.Fatal("ack")
	}
	if _, err := a.db.Exec("ALTER TABLE messages DROP COLUMN subtitle"); err != nil {
		t.Fatal(err)
	}
	if _, err := a.db.Exec("PRAGMA user_version=0"); err != nil {
		t.Fatal(err)
	}
	a.close()
	upgraded, err := openAPI(path)
	if err != nil {
		t.Fatal(err)
	}
	var subtitle string
	var acked int
	var sequence int64
	if err = upgraded.db.QueryRow("SELECT subtitle,acked,seq FROM messages WHERE id=?", old.ID).Scan(&subtitle, &acked, &sequence); err != nil || subtitle != "" || acked != 1 || sequence != old.Sequence {
		t.Fatal("migration lost state", err)
	}
	if upgraded.serverID != one.ServerID {
		t.Fatal("identity changed")
	}
	if _, err = upgraded.authenticateToken(one.ReceiveToken, false); err != nil {
		t.Fatal("credentials changed")
	}
	upgraded.close()
	reopened, err := openAPI(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.close()
	storedBark(t, reopened, requestAPI(reopened, "POST", "/"+one.PushKey, "", map[string]string{"body": "new", "subtitle": "retained"}))
}

func TestSubtitleMessagesAndDeviceIsolation(t *testing.T) {
	a, one, two, _ := setupAPI(t)
	storedBark(t, a, requestAPI(a, "POST", "/"+one.PushKey, "", map[string]string{"body": "B", "subtitle": "S"}))
	for _, c := range []struct {
		token string
		count int
	}{{one.ReceiveToken, 1}, {two.ReceiveToken, 0}} {
		w := requestAPI(a, "GET", "/api/v1/messages", c.token, nil)
		var page struct{ Messages []wireMessage }
		if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &page) != nil || len(page.Messages) != c.count {
			t.Fatal("isolation")
		}
		if c.count == 1 && page.Messages[0].Subtitle != "S" {
			t.Fatal("wire subtitle lost")
		}
	}
}
