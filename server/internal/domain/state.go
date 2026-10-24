package domain

import (
	"crypto/sha256"
	"encoding/json"
	"errors"
	"unicode"
	"unicode/utf8"
)

const StructuredSchemaVersion = 1
const MaxStateRevision int64 = 9007199254740991

var (
	ErrStaleStateRevision    = errors.New("stale_state_revision")
	ErrStateRevisionConflict = errors.New("state_revision_conflict")
	ErrStateTransition       = errors.New("invalid_state_transition")
)

// StateIdentity is scoped using authenticated identities, never request application IDs.
type StateIdentity struct {
	DeviceID      string
	ApplicationID string
	TopicKey      string
}

type StateVersion struct {
	Revision int64
	Digest   [32]byte
	Deleted  bool
	Task     bool
	Status   string
}

func ValidTopicKey(key string) bool {
	if len(key) == 0 || len(key) > 128 || !utf8.ValidString(key) {
		return false
	}
	for _, c := range key {
		if unicode.IsControl(c) {
			return false
		}
	}
	return true
}

func ValidStateStatus(task bool, status string) bool {
	if !task {
		return status == ""
	}
	switch status {
	case "running", "paused", "completed", "failed", "cancelled":
		return true
	}
	return false
}

func terminalState(status string) bool {
	return status == "completed" || status == "failed" || status == "cancelled"
}

// StateDigest compares semantic JSON rather than whitespace or object key order.
// Call only after the request has passed schema and numeric range validation.
func StateDigest(payload any) ([32]byte, error) {
	encoded, err := json.Marshal(payload)
	if err != nil {
		return [32]byte{}, err
	}
	return sha256.Sum256(encoded), nil
}

// CheckStateVersion must execute inside the same transaction as the state write.
// unchanged=true means the caller must not write another change or notify again.
func CheckStateVersion(previous *StateVersion, next StateVersion) (unchanged bool, err error) {
	if next.Revision < 1 || next.Revision > MaxStateRevision {
		return false, ErrInvalidMessage
	}
	if !next.Deleted && !ValidStateStatus(next.Task, next.Status) {
		return false, ErrInvalidMessage
	}
	if previous == nil {
		return false, nil
	}
	if next.Revision < previous.Revision {
		return false, ErrStaleStateRevision
	}
	if next.Revision == previous.Revision {
		if next.Digest == previous.Digest && next.Deleted == previous.Deleted && next.Task == previous.Task && next.Status == previous.Status {
			return true, nil
		}
		return false, ErrStateRevisionConflict
	}
	if previous.Task != next.Task {
		return false, ErrStateTransition
	}
	if previous.Task && !next.Deleted && (previous.Deleted || terminalState(previous.Status) && previous.Status != next.Status) {
		return false, ErrStateTransition
	}
	return false, nil
}
