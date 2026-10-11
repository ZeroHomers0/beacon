package httpapi

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode/utf8"

	"beacon.local/server/internal/domain"
)

const barkRequestLimit = domain.MaxRequestBytes

func barkReply(w http.ResponseWriter, status int, message string, stored *domain.Message) {
	result := map[string]any{"code": status, "message": message, "timestamp": time.Now().Unix()}
	if stored != nil {
		result["id"] = stored.ID
		result["sequence"] = stored.Sequence
	}
	writeJSON(w, status, result)
}

// barkField reports whether Beacon honours a Bark parameter. Every other Bark
// parameter (group, badge, ttl, action, ...) is silently dropped so third-party
// software only has to change the key to reach Beacon.
func barkField(key string) bool {
	return key == "title" || key == "subtitle" || key == "body" || key == "device_key" || key == "level" || key == "sound" || key == "image" || key == "icon" || key == "url" || key == "ciphertext" || key == "iv"
}

// barkValues parses a Bark query string or urlencoded form. Unsupported
// parameters are dropped, including a repeated unsupported parameter. A
// repeated *supported* parameter stays a malformed request: the server cannot
// tell which of the values the client meant, so the request is rejected.
func barkValues(raw string) (map[string]string, error) {
	parsed, err := url.ParseQuery(raw)
	if err != nil {
		return nil, err
	}
	result := map[string]string{}
	for key, values := range parsed {
		if !barkField(key) {
			continue
		}
		if len(values) != 1 || !utf8.ValidString(values[0]) {
			return nil, errors.New("invalid fields")
		}
		result[key] = values[0]
	}
	return result, nil
}

// barkJSON decodes a Bark JSON object. Unsupported parameters are dropped
// whatever their JSON type; a non-string value for a parameter Beacon actually
// honours is a genuine client bug and fails the request. A duplicate key is
// rejected for every key: encoding/json would silently keep the last value, and
// the Workers backend rejects it while parsing too.
func barkJSON(raw []byte) (map[string]string, error) {
	if !utf8.Valid(raw) {
		return nil, errors.New("invalid utf8")
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	token, err := decoder.Token()
	if err != nil || token != json.Delim('{') {
		return nil, errors.New("invalid object")
	}
	values := map[string]string{}
	seen := map[string]bool{}
	for decoder.More() {
		token, err = decoder.Token()
		key, ok := token.(string)
		if err != nil || !ok || seen[key] {
			return nil, errors.New("invalid field")
		}
		seen[key] = true
		supported := barkField(key)
		var rawValue json.RawMessage
		if err = decoder.Decode(&rawValue); err != nil || len(rawValue) == 0 {
			return nil, errors.New("invalid value")
		}
		if !supported {
			continue
		}
		if rawValue[0] != '"' {
			return nil, errors.New("invalid string")
		}
		var value string
		if err = json.Unmarshal(rawValue, &value); err != nil {
			return nil, err
		}
		values[key] = value
	}
	if _, err = decoder.Token(); err != nil {
		return nil, err
	}
	if _, err = decoder.Token(); err != io.EOF {
		return nil, errors.New("trailing payload")
	}
	return values, nil
}

// parseBarkPath splits the request path into Bark fields, decoding each field
// once. It reports the reply to send when the path is unusable.
func parseBarkPath(r *http.Request, isPush bool) (parts []string, status int, code string, ok bool) {
	// RequestURI retains encoded separators even when raw Unicode makes RawPath
	// unsuitable as an EscapedPath hint. Split before decoding each field once.
	rawPath := r.URL.EscapedPath()
	if strings.HasPrefix(r.RequestURI, "/") {
		rawPath = strings.SplitN(r.RequestURI, "?", 2)[0]
	}
	rawPath = strings.TrimPrefix(rawPath, "/")
	parts = strings.Split(rawPath, "/")
	if !isPush && (len(parts) < 1 || len(parts) > 4) {
		return nil, http.StatusNotFound, "not_found", false
	}
	for index, part := range parts {
		value, err := url.PathUnescape(part)
		if err != nil || !utf8.ValidString(value) {
			return nil, http.StatusBadRequest, "invalid_path", false
		}
		parts[index] = value
	}
	if !isPush && len(parts[0]) != domain.TokenLength {
		return nil, http.StatusNotFound, "not_found", false
	}
	return parts, 0, "", true
}

// barkBodyValues decodes and validates a POST body according to the declared
// media type. It reports the reply to send when the body is unusable.
func barkBodyValues(w http.ResponseWriter, r *http.Request) (map[string]string, int, string, bool) {
	media, params, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || (media != "application/json" && media != "application/x-www-form-urlencoded") || (params["charset"] != "" && !strings.EqualFold(params["charset"], "utf-8")) {
		return nil, http.StatusUnsupportedMediaType, "unsupported_media_type", false
	}
	r.Body = http.MaxBytesReader(w, r.Body, barkRequestLimit)
	defer r.Body.Close()
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		var limit *http.MaxBytesError
		if errors.As(err, &limit) {
			return nil, http.StatusRequestEntityTooLarge, "request_too_large", false
		}
		return nil, http.StatusBadRequest, "invalid_message", false
	}
	var values map[string]string
	if media == "application/json" {
		values, err = barkJSON(raw)
	} else {
		values, err = barkValues(string(raw))
	}
	if err != nil {
		return nil, http.StatusBadRequest, "invalid_message", false
	}
	return values, 0, "", true
}

func (a *API) bark(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/" {
		barkReply(w, http.StatusNotFound, "not_found", nil)
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		w.Header().Set("Allow", "GET, POST")
		barkReply(w, http.StatusMethodNotAllowed, "method_not_allowed", nil)
		return
	}
	isPush := r.URL.Path == "/push"
	if isPush && r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		barkReply(w, http.StatusMethodNotAllowed, "method_not_allowed", nil)
		return
	}
	parts, status, code, ok := parseBarkPath(r, isPush)
	if !ok {
		barkReply(w, status, code, nil)
		return
	}
	if len(r.URL.RawQuery) > barkRequestLimit {
		barkReply(w, http.StatusRequestEntityTooLarge, "request_too_large", nil)
		return
	}
	values := map[string]string{}
	if r.Method == http.MethodPost {
		values, status, code, ok = barkBodyValues(w, r)
		if !ok {
			barkReply(w, status, code, nil)
			return
		}
	}
	query, err := barkValues(r.URL.RawQuery)
	if err != nil {
		barkReply(w, http.StatusBadRequest, "invalid_query", nil)
		return
	}
	for key, value := range query {
		values[key] = value
	}
	if !isPush {
		values["device_key"] = parts[0]
		switch len(parts) {
		case 2:
			values["body"] = parts[1]
		case 3:
			values["title"], values["body"] = parts[1], parts[2]
		case 4:
			values["title"], values["subtitle"], values["body"] = parts[1], parts[2], parts[3]
		}
	}
	_, err = a.service.Authenticate(values["device_key"], true)
	if err != nil {
		barkReply(w, http.StatusUnauthorized, "unauthorized", nil)
		return
	}
	extension, kept := validateBarkExtension(values)
	// Bark's own semantics give ciphertext precedence: when it survives
	// validation, any plaintext alongside it is ignored rather than rejected.
	if kept["ciphertext"] != "" {
		values["title"], values["subtitle"] = "", ""
		values["body"] = "加密消息"
	}
	priority := barkPriority(values["level"], values["sound"])
	message, err := a.service.PushCredential(values["device_key"], values["title"], values["subtitle"], values["body"], &priority, extension)
	if err != nil {
		if errors.Is(err, domain.ErrInvalidMessage) {
			barkReply(w, http.StatusBadRequest, "invalid_message", nil)
		} else if errors.Is(err, domain.ErrStorageCapacity) {
			barkReply(w, http.StatusInsufficientStorage, "storage_capacity_reached", nil)
		} else if errors.Is(err, domain.ErrRateLimited) {
			w.Header().Set("Retry-After", "1")
			barkReply(w, http.StatusTooManyRequests, "push_rate_limited", nil)
		} else if errors.Is(err, domain.ErrInvalidDevice) {
			barkReply(w, http.StatusUnauthorized, "unauthorized", nil)
		} else {
			barkReply(w, http.StatusInternalServerError, "storage_error", nil)
		}
		return
	}
	barkReply(w, http.StatusOK, "success", &message)
}
