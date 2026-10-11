package config

import (
	"encoding/hex"
	"errors"
	"os"
	"strings"
)

// Web administration is disabled unless a dedicated 256-bit secret is supplied
// directly in the container environment or in a file readable by the process.
func WebAdminTokenFromEnv() (string, error) {
	if token := strings.TrimSpace(os.Getenv("BEACON_WEB_ADMIN_TOKEN")); token != "" {
		return validateWebAdminToken(token)
	}
	path := os.Getenv("BEACON_WEB_ADMIN_TOKEN_FILE")
	if path == "" {
		return "", nil
	}
	data, err := os.ReadFile(path)
	if err != nil || len(data) > 128 {
		return "", errors.New("web admin token unavailable")
	}
	return validateWebAdminToken(strings.TrimSpace(string(data)))
}

func validateWebAdminToken(token string) (string, error) {
	if len(token) != 64 {
		return "", errors.New("invalid web admin token")
	}
	if _, err := hex.DecodeString(token); err != nil {
		return "", errors.New("invalid web admin token")
	}
	return token, nil
}
