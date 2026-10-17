package httpapi

import (
	"encoding/base64"
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
		{"GET", "/" + one.PushKey + "?body=a&body=b", "", "", 400},
		{"GET", "/" + one.PushKey + "/%FF", "", "", 400},
		{"POST", "/" + one.PushKey, "text/plain", "body", 415},
		{"POST", "/" + one.PushKey, "application/x-www-form-urlencoded", "body=a&body=b", 400},
		{"POST", "/" + one.PushKey, "application/x-www-form-urlencoded", "body=" + strings.Repeat("x", barkRequestLimit), 413},
		{"POST", "/" + one.PushKey, "application/json", `{"body":null}`, 400},
		{"POST", "/" + one.PushKey, "application/json", `{"body":42}`, 400},
		{"POST", "/" + one.PushKey, "application/json", `{"body":["a"]}`, 400},
		{"POST", "/" + one.PushKey, "application/json", `{"title":123,"body":"x"}`, 400},
		// A duplicate supported key is a malformed request, not an unsupported field.
		{"POST", "/" + one.PushKey, "application/json", `{"body":"a","body":"b"}`, 400},
		{"POST", "/" + one.PushKey, "application/json", `{"body":"a","title":"x","title":"y"}`, 400},
		{"POST", "/" + one.PushKey, "application/json", `{"body":"a"} {}`, 400},
		{"POST", "/" + one.PushKey, "application/json", `{"body":"a","subtitle":"` + strings.Repeat("中", 342) + `"}`, 400},
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

// Bark parameters Beacon does not implement must be dropped, never rejected.
func TestBarkUnsupportedParametersAreDropped(t *testing.T) {
	a, one, _, _ := setupAPI(t)
	unsupported := map[string]string{
		"group": "team", "badge": "1", "copy": "x", "autoCopy": "1",
		"automaticallyCopy": "1", "isArchive": "1", "ttl": "3600", "action": "none",
	}
	// A JSON push body carrying every unsupported name alongside a valid body.
	body, err := json.Marshal(func() map[string]any {
		fields := map[string]any{"device_key": one.PushKey, "body": "kept"}
		for key, value := range unsupported {
			fields[key] = value
		}
		return fields
	}())
	if err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequest("POST", "/push", strings.NewReader(string(body)))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	a.handler().ServeHTTP(w, r)
	if m := storedBark(t, a, w); m.Body != "kept" {
		t.Fatalf("unsupported JSON fields rejected: %#v", m)
	}
	// The same unsupported set in the query string of a text push.
	query := url.Values{}
	for key, value := range unsupported {
		query.Set(key, value)
	}
	query.Set("body", "quoted")
	m := storedBark(t, a, requestAPI(a, "GET", "/"+one.PushKey+"/path?"+query.Encode(), "", nil))
	// Path fields still win over query values.
	if m.Body != "path" {
		t.Fatalf("unsupported query fields rejected: %#v", m)
	}
	// A JSON value of the wrong type is ignored when the name is unsupported.
	for _, raw := range []string{
		`{"device_key":"` + one.PushKey + `","body":"n","badge":1}`,
		`{"device_key":"` + one.PushKey + `","body":"s","volume":"50"}`,
		`{"device_key":"` + one.PushKey + `","body":"t","ttl":3600,"isArchive":true,"autoCopy":1,"volume":50}`,
	} {
		r := httptest.NewRequest("POST", "/push", strings.NewReader(raw))
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		a.handler().ServeHTTP(w, r)
		if w.Code != http.StatusOK {
			t.Fatalf("unsupported JSON types rejected: %d %s", w.Code, w.Body.String())
		}
	}
	// An unknown level degrades to the default priority instead of failing.
	response := requestAPI(a, "POST", "/push", "", map[string]string{"device_key": one.PushKey, "body": "level", "level": "bogus"})
	var result struct{ ID string }
	if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &result) != nil {
		t.Fatalf("unknown level rejected: %d %s", response.Code, response.Body.String())
	}
	var priority int
	if err := a.db.QueryRow("SELECT priority FROM messages WHERE id=?", result.ID).Scan(&priority); err != nil || priority != 5 {
		t.Fatalf("unknown level priority=%d err=%v", priority, err)
	}
}

// The full strict whitelist is gone: every unsupported form/query name is now
// silently dropped while supported fields keep working.
func TestBarkUnsupportedFormAndQueryNames(t *testing.T) {
	a, one, _, _ := setupAPI(t)
	form := url.Values{"title": {"T"}, "body": {"B"}, "group": {"g"}, "badge": {"9"}, "action": {"open"}}
	r := httptest.NewRequest("POST", "/"+one.PushKey, strings.NewReader(form.Encode()))
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	w := httptest.NewRecorder()
	a.handler().ServeHTTP(w, r)
	if m := storedBark(t, a, w); m.Title != "T" || m.Body != "B" {
		t.Fatalf("form dropped supported fields: %#v", m)
	}
	// A repeated unsupported key is irrelevant and is dropped too.
	m := storedBark(t, a, requestAPI(a, "GET", "/"+one.PushKey+"/body?badge=a&badge=b", "", nil))
	if m.Body != "body" {
		t.Fatalf("repeated unsupported key rejected or stored: %#v", m)
	}
}

// ciphertext wins over plaintext instead of failing the push, and an
// undecryptable payload is dropped without losing the message.
func TestBarkCiphertextPrecedenceAndDroppedExtensions(t *testing.T) {
	a, one, _, _ := setupAPI(t)
	encrypted := base64.StdEncoding.EncodeToString(make([]byte, 32))
	// Plaintext alongside a valid ciphertext is ignored, not rejected.
	response := requestAPI(a, "POST", "/push", "", map[string]string{
		"device_key": one.PushKey, "title": "T", "subtitle": "S", "body": "plain",
		"ciphertext": encrypted, "iv": "1234567890123456",
	})
	m := storedBark(t, a, response)
	if m.Body != "加密消息" || m.Title != "" || m.Subtitle != "" {
		t.Fatalf("ciphertext did not win: %#v", m)
	}
	var raw string
	if err := a.db.QueryRow("SELECT bark FROM messages WHERE id=?", m.ID).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	var extension map[string]string
	if json.Unmarshal([]byte(raw), &extension) != nil || extension["ciphertext"] != encrypted || extension["iv"] != "1234567890123456" {
		t.Fatalf("valid ciphertext dropped: %s", raw)
	}
	// An invalid iv drops the ciphertext pair but the plaintext body survives.
	m = storedBark(t, a, requestAPI(a, "POST", "/push", "", map[string]string{
		"device_key": one.PushKey, "body": "text", "ciphertext": encrypted, "iv": "short",
	}))
	if m.Body != "text" {
		t.Fatalf("invalid iv lost the message: %#v", m)
	}
	if err := a.db.QueryRow("SELECT bark FROM messages WHERE id=?", m.ID).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(raw, "ciphertext") || strings.Contains(raw, "iv") {
		t.Fatalf("undecryptable payload stored: %s", raw)
	}
	// An invalid ciphertext drops itself and the iv, keeping the plaintext body.
	m = storedBark(t, a, requestAPI(a, "POST", "/push", "", map[string]string{
		"device_key": one.PushKey, "body": "kept", "ciphertext": "not-base64!", "iv": "1234567890123456",
	}))
	if err := a.db.QueryRow("SELECT bark FROM messages WHERE id=?", m.ID).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(raw, "ciphertext") || strings.Contains(raw, "iv") {
		t.Fatalf("invalid ciphertext stored: %s", raw)
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
