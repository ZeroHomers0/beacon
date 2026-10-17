package domain

import (
	"strings"
	"testing"
)

func TestMessageTextBoundaries(t *testing.T) {
	for _, test := range []struct {
		name, title, subtitle, body string
		valid                       bool
	}{
		{"boundary", strings.Repeat("a", MaxTitleBytes), strings.Repeat("a", MaxTitleBytes), strings.Repeat("a", MaxBodyBytes), true},
		{"title overflow", strings.Repeat("a", MaxTitleBytes+1), "", "body", false},
		{"subtitle overflow", "", strings.Repeat("a", MaxTitleBytes+1), "body", false},
		{"body overflow", "", "", strings.Repeat("a", MaxBodyBytes+1), false},
		{"empty", "", "", "", false},
		{"unicode", "标题", "", "正常消息", true},
		{"invalid UTF-8", "", "", string([]byte{0xff}), false},
	} {
		t.Run(test.name, func(t *testing.T) {
			if ValidMessageText(test.title, test.subtitle, test.body) != test.valid {
				t.Fatal("unexpected validation result")
			}
		})
	}
}
