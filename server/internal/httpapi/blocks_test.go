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

func TestStructuredBlocksRoundtrip(t *testing.T) {
	a, device, _, path := setupAPI(t)
	send := func(blocks any) *httptest.ResponseRecorder {
		raw, _ := json.Marshal(map[string]any{"body": "build finished", "schema_version": 1, "blocks": blocks})
		r := httptest.NewRequest("POST", "/api/v1/push", bytes.NewReader(raw))
		r.Header.Set("Authorization", "Bearer "+device.PushKey)
		r.Header.Set("Idempotency-Key", "blocks-roundtrip")
		w := httptest.NewRecorder()
		a.handler().ServeHTTP(w, r)
		return w
	}
	value, max := 40.0, 100.0
	blocks := []domain.StructuredBlock{{Kind: "progress", ID: "download", Value: &value, Max: &max}}
	first := send(blocks)
	if first.Code != 200 {
		t.Fatal(first.Code, first.Body)
	}
	var stored domain.Message
	if err := json.Unmarshal(first.Body.Bytes(), &stored); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(stored.Blocks, blocks) {
		t.Fatal(stored)
	}
	replay := send(blocks)
	if replay.Code != 200 || replay.Body.String() != first.Body.String() {
		t.Fatal(replay.Code, replay.Body)
	}
	value2 := 41.0
	if conflict := send([]domain.StructuredBlock{{Kind: "progress", ID: "download", Value: &value2, Max: &max}}); conflict.Code != 409 {
		t.Fatal(conflict.Code, conflict.Body)
	}
	tasks, err := a.repository.ClaimPublish(time.Now().UnixMilli(), 10)
	if err != nil || len(tasks) != 1 || !reflect.DeepEqual(tasks[0].Message.Blocks, blocks) {
		t.Fatal(tasks, err)
	}
	reopened, err := openAPI(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.close()
	page, err := reopened.repository.Messages(device.DeviceID, 0, 10, false)
	if err != nil || len(page.Messages) != 1 || !reflect.DeepEqual(page.Messages[0].Blocks, blocks) {
		t.Fatal(page, err)
	}
}
