package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const validToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

func TestWebAdminTokenAcceptsInlineEnvironmentValue(t *testing.T) {
	t.Setenv("BEACON_WEB_ADMIN_TOKEN", validToken)
	t.Setenv("BEACON_WEB_ADMIN_TOKEN_FILE", "")
	token, err := WebAdminTokenFromEnv()
	if err != nil || token != validToken {
		t.Fatalf("inline token rejected: %q %v", token, err)
	}
}

func TestWebAdminTokenStaysDisabledWithoutConfiguration(t *testing.T) {
	t.Setenv("BEACON_WEB_ADMIN_TOKEN", "")
	t.Setenv("BEACON_WEB_ADMIN_TOKEN_FILE", "")
	token, err := WebAdminTokenFromEnv()
	if err != nil || token != "" {
		t.Fatalf("expected disabled administration: %q %v", token, err)
	}
}

func TestWebAdminTokenRejectsMalformedInlineValues(t *testing.T) {
	cases := map[string]string{
		"too short": strings.Repeat("a", 63),
		"too long":  strings.Repeat("a", 65),
		"not hex":   strings.Repeat("z", 64),
	}
	for name, value := range cases {
		t.Run(name, func(t *testing.T) {
			t.Setenv("BEACON_WEB_ADMIN_TOKEN", value)
			t.Setenv("BEACON_WEB_ADMIN_TOKEN_FILE", "")
			if _, err := WebAdminTokenFromEnv(); err == nil {
				t.Fatalf("malformed token accepted: %q", value)
			}
		})
	}
}

func TestWebAdminTokenFallsBackToFile(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "web-admin-token")
	if err := os.WriteFile(path, []byte(validToken+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("BEACON_WEB_ADMIN_TOKEN", "")
	t.Setenv("BEACON_WEB_ADMIN_TOKEN_FILE", path)
	token, err := WebAdminTokenFromEnv()
	if err != nil || token != validToken {
		t.Fatalf("file token rejected: %q %v", token, err)
	}
}

func TestWebAdminTokenPrefersInlineValueOverFile(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "web-admin-token")
	if err := os.WriteFile(path, []byte(strings.Repeat("a", 64)), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("BEACON_WEB_ADMIN_TOKEN", validToken)
	t.Setenv("BEACON_WEB_ADMIN_TOKEN_FILE", path)
	token, err := WebAdminTokenFromEnv()
	if err != nil || token != validToken {
		t.Fatalf("inline token did not win: %q %v", token, err)
	}
}
