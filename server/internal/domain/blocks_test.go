package domain

import (
	"encoding/json"
	"os"
	"testing"
)

func TestStructuredBlockValidation(t *testing.T) {
	for _, tc := range []struct {
		raw   string
		valid bool
	}{
		{`[{"kind":"progress","id":"download","value":40,"max":100}]`, true},
		{`[{"kind":"progress","id":"download","indeterminate":true}]`, true},
		{`[{"kind":"metric","id":"temperature","value":-10,"unit":"C"}]`, true},
		{`[{"kind":"metric","id":"temperature","value":10,"min":0}]`, false},
		{`[{"kind":"progress","id":"x","value":101,"max":100}]`, false},
		{`[{"kind":"progress","id":"x","value":0,"max":0}]`, false},
		{`[{"kind":"progress","id":"x","indeterminate":true,"value":1}]`, false},
		{`[{"kind":"metric","id":"x","value":1},{"kind":"metric","id":"x","value":2}]`, false},
		{`[{"kind":"html","id":"x"}]`, false},
		{`[{"kind":"metric","id":"x","value":1,"unexpected":true}]`, false},
		{`null`, false},
	} {
		var b StructuredBlocks
		err := json.Unmarshal([]byte(tc.raw), &b)
		if (err == nil) != tc.valid {
			t.Errorf("%s: %v", tc.raw, err)
		}
	}
}

func TestSharedStructuredBlockContract(t *testing.T) {
	raw, err := os.ReadFile("../../../contract/structured-blocks.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Name    string
		Version int
		Blocks  json.RawMessage
		Valid   bool
	}
	if err = json.Unmarshal(raw, &cases); err != nil {
		t.Fatal(err)
	}
	for _, tc := range cases {
		t.Run(tc.Name, func(t *testing.T) {
			var blocks StructuredBlocks
			err := json.Unmarshal(tc.Blocks, &blocks)
			valid := err == nil && ValidStructuredBlocks(tc.Version, blocks)
			if valid != tc.Valid {
				t.Fatalf("valid=%v error=%v", valid, err)
			}
		})
	}
}
