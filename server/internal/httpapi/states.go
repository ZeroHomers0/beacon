package httpapi

import (
	"beacon.local/server/internal/domain"
	"errors"
	"net/http"
	"strconv"
)

func stateError(w http.ResponseWriter, err error) {
	status, code := 500, "internal_error"
	switch {
	case errors.Is(err, domain.ErrUnauthorized):
		status, code = 401, "unauthorized"
	case errors.Is(err, domain.ErrInvalidMessage):
		status, code = 400, "invalid_message"
	case errors.Is(err, domain.ErrRateLimited):
		status, code = 429, "push_rate_limited"
		w.Header().Set("Retry-After", "60")
	case errors.Is(err, domain.ErrStorageCapacity):
		status, code = 507, "storage_capacity_reached"
	case errors.Is(err, domain.ErrStaleStateRevision), errors.Is(err, domain.ErrStateRevisionConflict), errors.Is(err, domain.ErrStateTransition):
		status, code = 409, err.Error()
	case err.Error() == "state_cursor_expired":
		status, code = 410, "state_cursor_expired"
	}
	apiError(w, status, code)
}
func (a *API) states(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, "GET") {
		return
	}
	device, err := a.authenticate(r, false)
	if err != nil {
		stateError(w, domain.ErrUnauthorized)
		return
	}
	q := r.URL.Query()
	for key := range q {
		if key != "cursor" && key != "snapshot_token" && key != "page_cursor" && key != "limit" {
			stateError(w, domain.ErrInvalidMessage)
			return
		}
	}
	limit := 100
	if q.Has("limit") {
		limit, err = strconv.Atoi(q.Get("limit"))
		if err != nil {
			stateError(w, domain.ErrInvalidMessage)
			return
		}
	}
	result, err := a.service.StatePage(device, q.Get("cursor"), q.Get("snapshot_token"), q.Get("page_cursor"), limit)
	if err != nil {
		stateError(w, err)
		return
	}
	writeJSON(w, 200, result)
}
func (a *API) stateMeta(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, "GET") {
		return
	}
	token, ok := bearerToken(r)
	if !ok {
		stateError(w, domain.ErrUnauthorized)
		return
	}
	topic := r.URL.Query().Get("topic_key")
	if !domain.ValidTopicKey(topic) {
		stateError(w, domain.ErrInvalidMessage)
		return
	}
	result, err := a.service.StateMeta(token, topic)
	if err != nil {
		stateError(w, err)
		return
	}
	writeJSON(w, 200, result)
}

func (a *API) pushCapabilities(w http.ResponseWriter, r *http.Request) {
	if !requireMethod(w, r, "GET") {
		return
	}
	token, ok := bearerToken(r)
	if !ok {
		stateError(w, domain.ErrUnauthorized)
		return
	}
	if _, err := a.service.StateDevice(token); err != nil {
		stateError(w, domain.ErrUnauthorized)
		return
	}
	writeJSON(w, 200, map[string]any{"state_protocol": 1, "structured_schema_versions": []int{1}, "block_kinds": domain.BlockKinds, "state_limits": map[string]int{"topics": 500, "bytes": 8388608, "per_application_per_minute": 120, "per_device_per_minute": 300}})
}
