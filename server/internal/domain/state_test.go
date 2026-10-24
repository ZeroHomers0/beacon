package domain

import (
	"errors"
	"strings"
	"testing"
)

func TestStateVersionLifecycle(t *testing.T) {
	digest, _ := StateDigest(map[string]any{"body": "download"})
	running := StateVersion{Revision: 2, Digest: digest, Task: true, Status: "running"}
	for _, tc := range []struct {
		name string
		prev *StateVersion
		next StateVersion
		same bool
		err  error
	}{
		{"new", nil, running, false, nil},
		{"retry", &running, running, true, nil},
		{"old", &running, StateVersion{Revision: 1, Task: true, Status: "running"}, false, ErrStaleStateRevision},
		{"conflict", &running, StateVersion{Revision: 2, Task: true, Status: "paused"}, false, ErrStateRevisionConflict},
		{"finish", &running, StateVersion{Revision: 3, Task: true, Status: "completed"}, false, nil},
		{"zero", nil, StateVersion{}, false, ErrInvalidMessage},
		{"unsafe integer", nil, StateVersion{Revision: MaxStateRevision + 1}, false, ErrInvalidMessage},
	} {
		t.Run(tc.name, func(t *testing.T) {
			same, err := CheckStateVersion(tc.prev, tc.next)
			if same != tc.same || !errors.Is(err, tc.err) {
				t.Fatalf("got %v %v", same, err)
			}
		})
	}
	completed := StateVersion{Revision: 3, Task: true, Status: "completed"}
	if _, err := CheckStateVersion(&completed, StateVersion{Revision: 4, Task: true, Status: "running"}); !errors.Is(err, ErrStateTransition) {
		t.Fatal(err)
	}
	deleted := StateVersion{Revision: 4, Task: true, Deleted: true}
	if _, err := CheckStateVersion(&deleted, StateVersion{Revision: 5, Task: true, Status: "running"}); !errors.Is(err, ErrStateTransition) {
		t.Fatal(err)
	}
	persistent := StateVersion{Revision: 4, Deleted: true}
	if _, err := CheckStateVersion(&persistent, StateVersion{Revision: 5}); err != nil {
		t.Fatal(err)
	}
}

func TestTopicKeyAndDigest(t *testing.T) {
	for _, key := range []string{"", strings.Repeat("a", 129), "a\nb", string([]byte{255})} {
		if ValidTopicKey(key) {
			t.Fatalf("accepted %q", key)
		}
	}
	if !ValidTopicKey("额度:账户一") {
		t.Fatal("unicode topic rejected")
	}
	a, _ := StateDigest(map[string]any{"a": 1, "b": 2})
	b, _ := StateDigest(map[string]any{"b": 2, "a": 1})
	if a != b {
		t.Fatal("unstable digest")
	}
}
