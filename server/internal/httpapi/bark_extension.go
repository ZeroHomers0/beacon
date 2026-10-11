package httpapi

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/url"
	"unicode/utf8"
)

// Only forward bounded presentation data. The server never fetches media or receives AES keys.
func validateBarkExtension(values map[string]string) (string, error) {
	extension := map[string]string{}
	for _, key := range []string{"image", "icon", "url", "sound", "ciphertext", "iv"} {
		value := values[key]
		if value == "" {
			continue
		}
		if !utf8.ValidString(value) {
			return "", errors.New("invalid extension")
		}
		switch key {
		case "image", "icon", "url":
			u, err := url.Parse(value)
			if err != nil || len(value) > 2048 || u.Scheme != "https" || u.Hostname() == "" || u.User != nil {
				return "", errors.New("invalid URL")
			}
		case "ciphertext":
			if len(value) > 90000 {
				return "", errors.New("ciphertext too large")
			}
			raw, err := base64.StdEncoding.DecodeString(value)
			if err != nil || len(raw) < 16 || len(raw) > 65552 || len(raw)%16 != 0 {
				return "", errors.New("invalid ciphertext")
			}
		case "iv":
			if len([]byte(value)) != 16 {
				return "", errors.New("invalid IV")
			}
		case "sound":
			if len(value) > 128 {
				return "", errors.New("invalid sound")
			}
		}
		extension[key] = value
	}
	if len(extension) == 0 {
		return "", nil
	}
	raw, err := json.Marshal(extension)
	return string(raw), err
}
