package httpapi

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestStateAdministrationDefaultSourceAndIsolation(t *testing.T) {
	a, d, _, _ := setupAPI(t)
	admin := strings.Repeat("b", 64)
	a.WithWebAdmin(admin)
	other, err := a.createDevice("other")
	if err != nil {
		t.Fatal(err)
	}
	app, err := a.service.CreateApplication(d.DeviceID, "monitor", 5)
	if err != nil {
		t.Fatal(err)
	}
	call := func(path, key string, p any, status int) map[string]any {
		t.Helper()
		w := requestAPI(a, "POST", path, key, p)
		if w.Code != status {
			t.Fatalf("%s: %d %s", path, w.Code, w.Body)
		}
		var result map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		return result
	}
	payload := map[string]any{"mode": "state", "schema_version": 1, "topic_key": "quota:shared", "revision": 1, "title": "quota", "body": "24%"}
	for _, key := range []string{d.PushKey, other.PushKey, app.Token} {
		call("/api/v1/push", key, payload, 200)
	}
	all := call("/api/admin/states/list", admin, map[string]any{}, 200)
	if all["total"] != float64(3) {
		t.Fatal(all)
	}
	defaultOnly := call("/api/admin/states/list", admin, map[string]any{"device_id": d.DeviceID, "application_id": ""}, 200)
	rows := defaultOnly["topics"].([]any)
	if len(rows) != 1 || rows[0].(map[string]any)["source_name"] != "默认来源" {
		t.Fatal(defaultOnly)
	}
	page := call("/api/admin/states/list", admin, map[string]any{"limit": 1}, 200)
	if page["has_more"] != true || page["next_offset"] != float64(1) {
		t.Fatal(page)
	}
	id := map[string]any{"device_id": d.DeviceID, "application_id": "", "topic_key": "quota:shared", "revision": 1}
	detail := call("/api/admin/states/detail", admin, id, 200)
	if detail["state"].(map[string]any)["body"] != "24%" {
		t.Fatal(detail)
	}
	for _, path := range []string{"list", "detail", "clear"} {
		call("/api/admin/states/"+path, d.PushKey, map[string]any{}, 401)
	}
	id["revision"] = 2
	call("/api/admin/states/clear", admin, id, 409)
	id["revision"] = 1
	call("/api/admin/states/clear", admin, id, 200)
	// Equal-version retry cannot resurrect an administratively cleared snapshot.
	retry := call("/api/v1/push", d.PushKey, payload, 200)
	if retry["deleted"] != true || retry["unchanged"] != true {
		t.Fatal(retry)
	}
	remain := call("/api/admin/states/list", admin, map[string]any{"state": "active"}, 200)
	if remain["total"] != float64(2) {
		t.Fatal(remain)
	}
	call("/api/admin/states/clear", admin, id, 200)
	payload["revision"] = 2
	call("/api/v1/push", d.PushKey, payload, 200)
	again := call("/api/admin/states/list", admin, map[string]any{"state": "active"}, 200)
	if again["total"] != float64(3) {
		t.Fatal(again)
	}
	call("/api/admin/states/list", admin, map[string]any{"limit": 101}, 400)
}
