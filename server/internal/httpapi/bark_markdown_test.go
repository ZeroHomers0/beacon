package httpapi

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestBarkMarkdownContract(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "contract", "bark-markdown.json"))
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Name    string
		Payload map[string]any
		Status  int
		Body    string
	}
	if err := json.Unmarshal(raw, &cases); err != nil {
		t.Fatal(err)
	}
	a, device, _, _ := setupAPI(t)
	for _, c := range cases {
		t.Run(c.Name, func(t *testing.T) {
			c.Payload["device_key"] = device.PushKey
			response := requestAPI(a, "POST", "/push", "", c.Payload)
			if response.Code != c.Status {
				t.Fatalf("status %d: %s", response.Code, response.Body.String())
			}
			if c.Status == 200 && storedBark(t, a, response).Body != c.Body {
				t.Fatal("wrong stored body")
			}
		})
	}
	for _, query := range []string{"markdown=a&markdown=b", "markdown=" + strings.Repeat("x", 65537)} {
		if response := requestAPI(a, "GET", "/"+device.PushKey+"?"+query, "", nil); response.Code != 400 {
			t.Fatal("accepted invalid markdown", response.Code)
		}
	}
	response := requestAPI(a, "GET", "/"+device.PushKey+"/path?markdown=query", "", nil)
	if storedBark(t, a, response).Body != "query" {
		t.Fatal("markdown did not override path body")
	}
}
