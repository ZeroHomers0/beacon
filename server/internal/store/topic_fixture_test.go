package store

import (
	"beacon.local/server/internal/domain"
	"errors"
	"testing"
)

func saveRegisteredState(t *testing.T, s *Store, token string, p domain.StatePayload) (domain.StateRecord, error) {
	t.Helper()
	if _, err := s.StateMeta(token, p.TopicKey); errors.Is(err, domain.ErrTopicNotFound) {
		tx, e := s.db.Begin()
		if e != nil {
			t.Fatal(e)
		}
		d, a, _, e := stateSender(tx, token)
		tx.Rollback()
		if e != nil {
			t.Fatal(e)
		}
		if _, e = s.MutateTopic("create", domain.TopicMutation{Device: d, Application: a, Topic: p.TopicKey, Name: p.TopicKey, RequestID: a + "/" + p.TopicKey}); e != nil {
			t.Fatal(e)
		}
	}
	return s.SaveState(token, p)
}
