package httpapi

import (
	"encoding/base64"
	"encoding/json"
	"net/url"
	"unicode/utf8"
)

// Only forward bounded presentation data. The server never fetches media or receives AES keys.
//
// A field that fails its own validation is dropped rather than failing the whole
// push: a third-party Bark client must not lose its message because it sent one
// unsupported or malformed presentation field. ciphertext and iv are coupled on
// purpose: an undecryptable payload is never stored, so either half failing
// drops both.
//
// The first return value is the marshalled surviving extension ("" when nothing
// survives); the second is the surviving fields, so the caller can tell whether
// the ciphertext was kept.
func validateBarkExtension(values map[string]string) (string, map[string]string) {
	extension := map[string]string{}
	for _, key := range []string{"image", "icon", "url", "sound"} {
		value := values[key]
		if value == "" || !utf8.ValidString(value) {
			continue
		}
		switch key {
		case "image", "icon", "url":
			u, err := url.Parse(value)
			if err != nil || len(value) > 2048 || u.Scheme != "https" || u.Hostname() == "" || u.User != nil {
				continue
			}
		case "sound":
			if len(value) > 128 {
				continue
			}
		}
		extension[key] = value
	}
	ciphertext := values["ciphertext"]
	validCiphertext := decodeBarkCiphertext(ciphertext)
	iv := values["iv"]
	validIV := iv != "" && utf8.ValidString(iv) && len([]byte(iv)) == 16
	if (ciphertext != "" && validCiphertext == "") || (iv != "" && !validIV) {
		validCiphertext, validIV = "", false
	}
	if validCiphertext != "" {
		extension["ciphertext"] = validCiphertext
	}
	if validIV {
		extension["iv"] = iv
	}
	if len(extension) == 0 {
		return "", nil
	}
	raw, err := json.Marshal(extension)
	if err != nil {
		return "", nil
	}
	return string(raw), extension
}

// decodeBarkCiphertext returns the ciphertext when it is a bounded standard
// base64 AES block sequence, and "" when it must be dropped.
func decodeBarkCiphertext(value string) string {
	if value == "" || len(value) > 90000 || !utf8.ValidString(value) {
		return ""
	}
	raw, err := base64.StdEncoding.DecodeString(value)
	if err != nil || len(raw) < 16 || len(raw) > 65552 || len(raw)%16 != 0 {
		return ""
	}
	return value
}
