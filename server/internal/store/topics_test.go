package store

import (
	"beacon.local/server/internal/domain"
	"errors"
	"fmt"
	"path/filepath"
	"sync"
	"testing"
)

func TestTopicRegistrationLifecycleAndHardDeletion(t *testing.T) {
	s, e := Open(filepath.Join(t.TempDir(), "topic.db"))
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	d, _ := s.CreateDevice("phone")
	p := domain.StatePayload{Mode: "state", SchemaVersion: 1, TopicKey: "quota", Revision: 1, Body: "40%"}
	if _, e = s.SaveState(d.PushKey, p); !errors.Is(e, domain.ErrTopicNotFound) {
		t.Fatal(e)
	}
	if _, e = s.StateMeta(d.PushKey, p.TopicKey); !errors.Is(e, domain.ErrTopicNotFound) {
		t.Fatal(e)
	}
	var count int
	s.db.QueryRow("SELECT count(*) FROM state_rate").Scan(&count)
	if count != 0 {
		t.Fatal("unknown topic consumed rate")
	}
	q := domain.TopicMutation{Device: d.DeviceID, Topic: p.TopicKey, Name: "Quota", RequestID: "request1"}
	result, e := s.MutateTopic("create", q)
	if e != nil {
		t.Fatal(e)
	}
	first := result["topic"].(domain.TopicRecord)
	if _, e = s.MutateTopic("create", q); e != nil {
		t.Fatal("retry", e)
	}
	meta, e := s.StateMeta(d.PushKey, p.TopicKey)
	if e != nil || meta["revision"] != 0 {
		t.Fatal(meta, e)
	}
	page, e := s.TopicPage(d.DeviceID, "", "", "", 1)
	if e != nil || len(page["topics"].([]domain.TopicRecord)) != 1 {
		t.Fatal(page, e)
	}
	if _, e = s.SaveState(d.PushKey, p); e != nil {
		t.Fatal(e)
	}
	q.Version = first.Version
	q.Name = "Renamed"
	if _, e = s.MutateTopic("update", q); e != nil {
		t.Fatal(e)
	}
	if _, e = s.MutateTopic("delete", q); !errors.Is(e, domain.ErrTopicConflict) {
		t.Fatal("version guard", e)
	}
	q.Version = 2
	if _, e = s.MutateTopic("delete", q); e != nil {
		t.Fatal(e)
	}
	if _, e = s.AdminTopic(q); !errors.Is(e, domain.ErrTopicNotFound) {
		t.Fatal(e)
	}
	if _, e = s.StateMeta(d.PushKey, p.TopicKey); !errors.Is(e, domain.ErrTopicNotFound) {
		t.Fatal(e)
	}
	if _, e = s.SaveState(d.PushKey, p); !errors.Is(e, domain.ErrTopicNotFound) {
		t.Fatal(e)
	}
	listed, e := s.AdminTopics(domain.StateAdminQuery{})
	if e != nil || listed["total"] != 0 {
		t.Fatal(listed, e)
	}
	delta, e := s.TopicPage(d.DeviceID, page["next_cursor"].(string), "", "", 100)
	if e != nil {
		t.Fatal(e)
	}
	items := delta["topics"].([]domain.TopicRecord)
	if len(items) != 2 || !items[1].Deleted {
		t.Fatal(items)
	}
	s.db.QueryRow("SELECT count(*) FROM current_states").Scan(&count)
	if count != 0 {
		t.Fatal("data retained")
	}
	q.RequestID = "request2"
	if _, e = s.MutateTopic("create", q); e != nil {
		t.Fatal(e)
	}
	if _, e = s.SaveState(d.PushKey, p); e != nil {
		t.Fatal("manual recreate starts fresh", e)
	}
}
func TestTopicConcurrentCreateScopeAndQuota(t *testing.T) {
	s, e := Open(filepath.Join(t.TempDir(), "topic.db"))
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	d, _ := s.CreateDevice("phone")
	other, _ := s.CreateDevice("other")
	app, _ := s.CreateApplication(other.DeviceID, "app", 5)
	q := domain.TopicMutation{Device: d.DeviceID, Application: app.ID, Topic: "same", Name: "Test", RequestID: "same"}
	if _, e = s.MutateTopic("create", q); !errors.Is(e, domain.ErrInvalidMessage) {
		t.Fatal(e)
	}
	q.Application = ""
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, e := s.MutateTopic("create", q); e != nil {
				t.Error(e)
			}
		}()
	}
	wg.Wait()
	var count int
	s.db.QueryRow("SELECT count(*) FROM topic_catalog").Scan(&count)
	if count != 1 {
		t.Fatal(count)
	}
	for i := 1; i < 500; i++ {
		q.Topic = fmt.Sprint("q", i)
		q.RequestID = q.Topic
		if _, e = s.MutateTopic("create", q); e != nil {
			t.Fatal(i, e)
		}
	}
	q.Topic = "overflow"
	q.RequestID = q.Topic
	if _, e = s.MutateTopic("create", q); !errors.Is(e, domain.ErrStorageCapacity) {
		t.Fatal(e)
	}
	q.Topic = "same"
	q.Version = 1
	if _, e = s.MutateTopic("delete", q); e != nil {
		t.Fatal(e)
	}
	q.Topic = "overflow"
	if _, e = s.MutateTopic("create", q); e != nil {
		t.Fatal("delete must release quota", e)
	}
}
func TestTopicCatalogBackfillsExistingStatesOnlyOnce(t *testing.T) {
	path := filepath.Join(t.TempDir(), "migration.db")
	s, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	d, _ := s.CreateDevice("phone")
	p := domain.StatePayload{Mode: "state", SchemaVersion: 1, TopicKey: "legacy", Revision: 9, Body: "legacy"}
	if _, e = saveRegisteredState(t, s, d.PushKey, p); e != nil {
		t.Fatal(e)
	}
	for _, q := range []string{"DROP TABLE topic_catalog", "DROP TABLE topic_changes", "DROP TABLE topic_snapshots", "PRAGMA user_version=13"} {
		if _, e = s.db.Exec(q); e != nil {
			t.Fatal(e)
		}
	}
	s.Close()
	s, e = Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	if _, e = s.AdminTopic(domain.TopicMutation{Device: d.DeviceID, Topic: p.TopicKey}); e != nil {
		t.Fatal(e)
	}
	meta, e := s.StateMeta(d.PushKey, p.TopicKey)
	if e != nil || meta["revision"] != int64(9) {
		t.Fatal(meta, e)
	}
	if _, e = s.db.Exec("DELETE FROM topic_catalog"); e != nil {
		t.Fatal(e)
	}
	if e = migrateMessageSchema(s.db); e != nil {
		t.Fatal(e)
	}
	if _, e = s.AdminTopic(domain.TopicMutation{Device: d.DeviceID, Topic: p.TopicKey}); !errors.Is(e, domain.ErrTopicNotFound) {
		t.Fatal("migration resurrected a deleted catalog", e)
	}
}
