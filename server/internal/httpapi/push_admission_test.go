package httpapi

import (
	"net/http"
	"testing"
)

func TestPushRateLimitRejectsWithoutSavingOrAffectingOtherDevice(t *testing.T) {
	a, one, two, _ := setupAPI(t)
	body := map[string]string{"title": "limit", "body": "body"}
	accepted := int64(0)
	limited := false
	for i := 0; i < 400; i++ {
		response := requestAPI(a, "POST", "/api/v1/push", one.PushKey, body)
		if response.Code == http.StatusTooManyRequests {
			if response.Header().Get("Retry-After") != "1" {
				t.Fatal("missing explicit retry contract")
			}
			limited = true
			break
		}
		if response.Code != http.StatusOK {
			t.Fatal("early rejection", response.Code)
		}
		accepted++
	}
	if !limited || accepted < 200 {
		t.Fatal("burst admission was not enforced", accepted)
	}
	stats, err := a.service.StorageStats()
	if err != nil || stats.Messages != accepted || stats.QueuedRealtime != accepted {
		t.Fatal("rejected request persisted data", stats, err)
	}
	if result := requestAPI(a, "POST", "/api/v1/push", two.PushKey, body); result.Code != http.StatusOK {
		t.Fatal("other device affected", result.Code)
	}
}
