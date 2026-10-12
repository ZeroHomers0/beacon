package domain

import (
	"bytes"
	"encoding/json"
	"net"
	"net/url"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

type MessageAction struct {
	Title string `json:"title"`
	URL   string `json:"url"`
}
type MessageActions []MessageAction

func (a *MessageActions) UnmarshalJSON(data []byte) error {
	if bytes.Equal(bytes.TrimSpace(data), []byte("null")) {
		return ErrInvalidMessage
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	var actions []MessageAction
	if err := decoder.Decode(&actions); err != nil {
		return err
	}
	if !ValidMessageActions(actions) {
		return ErrInvalidMessage
	}
	*a = actions
	return nil
}
func ValidMessageActions(actions []MessageAction) bool {
	if len(actions) > 3 {
		return false
	}
	for _, action := range actions {
		if strings.TrimSpace(action.Title) == "" || len(action.Title) > 64 || !utf8.ValidString(action.Title) {
			return false
		}
		for _, c := range action.Title {
			if unicode.IsControl(c) {
				return false
			}
		}
		if len(action.URL) > 2048 || !utf8.ValidString(action.URL) {
			return false
		}
		u, err := url.Parse(action.URL)
		if err != nil || !strings.EqualFold(u.Scheme, "https") || u.Opaque != "" || u.User != nil || u.Host == "" || u.Hostname() == "" {
			return false
		}
		host := u.Hostname()
		if strings.Contains(u.Host, ":") && net.ParseIP(host) == nil && u.Port() == "" {
			return false
		}
		if strings.HasSuffix(u.Host, ":") {
			return false
		}
		if port := u.Port(); port != "" {
			n, err := strconv.Atoi(port)
			if err != nil || n < 1 || n > 65535 {
				return false
			}
		}
		if net.ParseIP(host) == nil {
			if len(host) > 253 {
				return false
			}
			for _, label := range strings.Split(strings.TrimSuffix(host, "."), ".") {
				if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
					return false
				}
				for _, c := range label {
					if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-') {
						return false
					}
				}
			}
		}
		for _, c := range action.URL {
			if unicode.IsControl(c) || unicode.IsSpace(c) {
				return false
			}
		}
	}
	return true
}

// UnmarshalJSON preserves strict action objects, including duplicate-field rejection.
func (a *MessageAction) UnmarshalJSON(data []byte) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	token, err := decoder.Token()
	if err != nil || token != json.Delim('{') {
		return ErrInvalidMessage
	}
	seen := make(map[string]bool)
	for decoder.More() {
		token, err := decoder.Token()
		if err != nil {
			return err
		}
		key, ok := token.(string)
		if !ok || seen[key] || (key != "title" && key != "url") {
			return ErrInvalidMessage
		}
		seen[key] = true
		var raw json.RawMessage
		if err := decoder.Decode(&raw); err != nil {
			return err
		}
		if bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
			return ErrInvalidMessage
		}
		var value string
		if err := json.Unmarshal(raw, &value); err != nil {
			return err
		}
		if key == "title" {
			a.Title = value
		} else {
			a.URL = value
		}
	}
	if _, err := decoder.Token(); err != nil {
		return err
	}
	if !seen["title"] || !seen["url"] {
		return ErrInvalidMessage
	}
	return nil
}
