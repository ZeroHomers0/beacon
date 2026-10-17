package httpapi

import "testing"

func TestJSONUnicodeBoundaries(t *testing.T) {
	for _, test := range []struct {
		raw   string
		valid bool
	}{
		{`{"body":"hello"}`, true},
		{`{"body":"\ud83d\ude00"}`, true},
		{`{"body":"\\ud800"}`, true},
		{`{"body":"\ud800"}`, false},
		{`{"body":"\udc00"}`, false},
		{`{"body":"\ud800\u0041"}`, false},
		{`{"body":"\ud800`, false},
	} {
		if validJSONUnicode([]byte(test.raw)) != test.valid {
			t.Errorf("unexpected Unicode validation: %s", test.raw)
		}
	}
}
