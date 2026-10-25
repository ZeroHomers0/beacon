package store

import (
	"beacon.local/server/internal/domain"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
	"time"
	"unicode/utf8"
)

func createTopicSchema(tx *sql.Tx) error {
	for _, q := range []string{
		`CREATE TABLE IF NOT EXISTS topic_catalog(device TEXT NOT NULL REFERENCES devices(id),application TEXT NOT NULL,topic TEXT NOT NULL,name TEXT NOT NULL,version INTEGER NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,deleted INTEGER NOT NULL,request_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(device,application,topic))`,
		`CREATE UNIQUE INDEX IF NOT EXISTS topic_request ON topic_catalog(device,request_id) WHERE request_id<>''`,
		`CREATE TABLE IF NOT EXISTS topic_changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,device TEXT NOT NULL,application TEXT NOT NULL,topic TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)`,
		`CREATE INDEX IF NOT EXISTS topic_changes_device ON topic_changes(device,seq)`,
		`CREATE TABLE IF NOT EXISTS topic_snapshots(token TEXT PRIMARY KEY,device TEXT NOT NULL,payload TEXT NOT NULL,watermark INTEGER NOT NULL,expires INTEGER NOT NULL)`,
	} {
		if _, err := tx.Exec(q); err != nil {
			return err
		}
	}
	return nil
}
func checkTopic(tx *sql.Tx, device, app, topic string) error {
	var deleted bool
	err := tx.QueryRow("SELECT deleted FROM topic_catalog WHERE device=? AND application=? AND topic=?", device, app, topic).Scan(&deleted)
	if errors.Is(err, sql.ErrNoRows) {
		return domain.ErrTopicNotFound
	}
	if err != nil {
		return err
	}
	if deleted {
		return domain.ErrTopicDeleted
	}
	return nil
}
func (s *Store) MutateTopic(action string, p domain.TopicMutation) (map[string]any, error) {
	p.Name = strings.TrimSpace(p.Name)
	if p.Device == "" || (action == "create" && (p.Name == "" || len(p.RequestID) < 1 || len(p.RequestID) > 128)) || utf8.RuneCountInString(p.Name) > 80 || (action == "update" && p.Name == "") {
		return nil, domain.ErrInvalidMessage
	}
	if action == "create" && p.Topic == "" {
		h := sha256.Sum256([]byte(p.Device + ":" + p.RequestID))
		p.Topic = "topic_" + hex.EncodeToString(h[:12])
	}
	if !domain.ValidTopicKey(p.Topic) {
		return nil, domain.ErrInvalidMessage
	}
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var active int
	if err = tx.QueryRow("SELECT count(*) FROM devices WHERE id=? AND revoked=0", p.Device).Scan(&active); err != nil {
		return nil, err
	}
	if active != 1 {
		return nil, domain.ErrInvalidMessage
	}
	appName := ""
	if p.Application != "" {
		if err = tx.QueryRow("SELECT name FROM applications WHERE id=? AND device=? AND revoked=0", p.Application, p.Device).Scan(&appName); err != nil {
			return nil, domain.ErrInvalidMessage
		}
	}
	var raw string
	var request string
	if action == "create" {
		err = tx.QueryRow("SELECT payload,request_id FROM topic_catalog WHERE device=? AND request_id=?", p.Device, p.RequestID).Scan(&raw, &request)
		if err == nil {
			var old domain.TopicRecord
			if err = json.Unmarshal([]byte(raw), &old); err != nil {
				return nil, err
			}
			if old.Application != p.Application || old.Topic != p.Topic || old.Name != p.Name || old.Deleted {
				return nil, domain.ErrTopicConflict
			}
			return map[string]any{"topic": old}, nil
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return nil, err
		}
	}
	err = tx.QueryRow("SELECT payload,request_id FROM topic_catalog WHERE device=? AND application=? AND topic=?", p.Device, p.Application, p.Topic).Scan(&raw, &request)
	var record domain.TopicRecord
	now := time.Now().UnixMilli()
	if action == "create" {
		if err == nil {
			return nil, domain.ErrTopicConflict
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return nil, err
		}
		var count int
		if err = tx.QueryRow("SELECT count(*) FROM topic_catalog WHERE device=?", p.Device).Scan(&count); err != nil {
			return nil, err
		}
		if count >= 500 {
			return nil, domain.ErrStorageCapacity
		}
		record = domain.TopicRecord{Device: p.Device, Application: p.Application, Topic: p.Topic, Name: p.Name, ApplicationName: appName, Version: 1, Created: now, Updated: now}
		request = p.RequestID
	} else {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, domain.ErrTopicNotFound
		}
		if err != nil {
			return nil, err
		}
		if err = json.Unmarshal([]byte(raw), &record); err != nil {
			return nil, err
		}
		if p.Version != record.Version || record.Version >= domain.MaxStateRevision {
			return nil, domain.ErrTopicConflict
		}
		switch action {
		case "update":
			if record.Deleted {
				return nil, domain.ErrTopicDeleted
			}
			record.Name = p.Name
		case "delete":
			record.Deleted = true
		default:
			return nil, domain.ErrInvalidMessage
		}
		record.Version++
		record.Updated = now
		record.ApplicationName = appName
	}
	encoded, _ := json.Marshal(record)
	if err = stateStorageCapacity(tx, int64(len(encoded)*3)+domain.MaxRequestBytes); action != "delete" && err != nil {
		return nil, err
	}
	if action == "delete" {
		for _, table := range []string{"state_changes"} {
			if _, err = tx.Exec("DELETE FROM "+table+" WHERE device=? AND application=? AND topic=?", record.Device, record.Application, record.Topic); err != nil {
				return nil, err
			}
		}
		for _, table := range []string{"state_snapshots", "topic_snapshots"} {
			if _, err = tx.Exec("DELETE FROM "+table+" WHERE device=?", record.Device); err != nil {
				return nil, err
			}
		}
		if _, err = tx.Exec("DELETE FROM topic_catalog WHERE device=? AND application=? AND topic=?", record.Device, record.Application, record.Topic); err != nil {
			return nil, err
		}
		var data string
		err = tx.QueryRow("SELECT payload FROM current_states WHERE device=? AND application=? AND topic=?", record.Device, record.Application, record.Topic).Scan(&data)
		if err == nil {
			var state domain.StateRecord
			if err = json.Unmarshal([]byte(data), &state); err != nil {
				return nil, err
			}
			state = domain.StateRecord{StatePayload: domain.StatePayload{Mode: "state", SchemaVersion: 1, TopicKey: record.Topic, Revision: state.Revision, Operation: "delete"}, ApplicationID: record.Application, Deleted: true}
			raw, _ := json.Marshal(state)
			if err = appendStateChange(tx, record.Device, record.Application, record.Topic, string(raw), now); err != nil {
				return nil, err
			}
		}
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return nil, err
		}
		if _, err = tx.Exec("DELETE FROM current_states WHERE device=? AND application=? AND topic=?", record.Device, record.Application, record.Topic); err != nil {
			return nil, err
		}
	} else if _, err = tx.Exec(`INSERT INTO topic_catalog VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(device,application,topic) DO UPDATE SET name=excluded.name,version=excluded.version,updated=excluded.updated,deleted=excluded.deleted,payload=excluded.payload`, record.Device, record.Application, record.Topic, record.Name, record.Version, record.Created, record.Updated, record.Deleted, request, string(encoded)); err != nil {
		return nil, err
	}
	if _, err = tx.Exec("INSERT INTO topic_changes(device,application,topic,payload,created) VALUES(?,?,?,?,?)", record.Device, record.Application, record.Topic, string(encoded), now); err != nil {
		return nil, err
	}
	if _, err = tx.Exec("DELETE FROM topic_changes WHERE device=? AND (created<? OR seq < coalesce((SELECT seq FROM topic_changes WHERE device=? ORDER BY seq DESC LIMIT 1 OFFSET 9999),0))", record.Device, now-86400000, record.Device); err != nil {
		return nil, err
	}
	return map[string]any{"topic": record}, tx.Commit()
}
func (s *Store) AdminTopics(q domain.StateAdminQuery) (map[string]any, error) {
	if q.Limit == 0 {
		q.Limit = 50
	}
	if q.Limit < 1 || q.Limit > 100 || q.Offset < 0 || q.Offset > 1000000 || utf8.RuneCountInString(q.Search) > 128 {
		return nil, domain.ErrInvalidMessage
	}
	switch q.State {
	case "", "all", "waiting", "active", "stale", "expired", "deleted":
	default:
		return nil, domain.ErrInvalidMessage
	}
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	rows, err := tx.Query(`SELECT t.payload,d.name,coalesce(a.name,''),coalesce(s.payload,'') FROM topic_catalog t JOIN devices d ON d.id=t.device LEFT JOIN applications a ON a.id=t.application LEFT JOIN current_states s ON s.device=t.device AND s.application=t.application AND s.topic=t.topic WHERE (?='' OR t.device=?) ORDER BY t.device,t.application,t.topic`, q.Device, q.Device)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []map[string]any{}
	now := time.Now().UnixMilli()
	for rows.Next() {
		var raw, deviceName, appName, data string
		if err = rows.Scan(&raw, &deviceName, &appName, &data); err != nil {
			return nil, err
		}
		var topic domain.TopicRecord
		if err = json.Unmarshal([]byte(raw), &topic); err != nil {
			return nil, err
		}
		if q.Application != nil && topic.Application != *q.Application {
			continue
		}
		var state domain.StateRecord
		if data != "" {
			if err = json.Unmarshal([]byte(data), &state); err != nil {
				return nil, err
			}
		}
		status := "active"
		switch {
		case topic.Deleted:
			status = "deleted"
		case state.Revision == 0:
			status = "waiting"
		case state.ExpiresAt > 0 && state.ExpiresAt <= now:
			status = "expired"
		case state.Deleted:
			status = "waiting"
		case state.StaleAt > 0 && state.StaleAt <= now:
			status = "stale"
		}
		title := topic.Name
		if title == "" {
			title = state.Title
		}
		if title == "" {
			title = topic.Topic
		}
		if q.State != "" && q.State != "all" && q.State != status {
			continue
		}
		if q.Search != "" && !strings.Contains(strings.ToLower(title+" "+topic.Topic+" "+appName+" "+deviceName), strings.ToLower(q.Search)) {
			continue
		}
		source := appName
		if topic.Application == "" {
			source = "未关联应用"
		}
		items = append(items, map[string]any{"device_id": topic.Device, "device_name": deviceName, "application_id": topic.Application, "source_name": source, "topic_key": topic.Topic, "name": topic.Name, "title": title, "config_version": topic.Version, "deleted": topic.Deleted, "state": status, "revision": state.Revision, "status": state.Status, "observed_at_ms": state.ObservedAt, "accepted_at_ms": state.AcceptedAt, "expires_at_ms": state.ExpiresAt})
	}
	if err = rows.Err(); err != nil {
		return nil, err
	}
	total := len(items)
	start := min(q.Offset, total)
	end := min(start+q.Limit, total)
	return map[string]any{"topics": items[start:end], "total": total, "has_more": end < total, "next_offset": end}, nil
}
func (s *Store) AdminTopic(p domain.TopicMutation) (map[string]any, error) {
	var raw string
	err := s.db.QueryRow("SELECT payload FROM topic_catalog WHERE device=? AND application=? AND topic=?", p.Device, p.Application, p.Topic).Scan(&raw)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, domain.ErrTopicNotFound
	}
	if err != nil {
		return nil, err
	}
	var topic domain.TopicRecord
	if err = json.Unmarshal([]byte(raw), &topic); err != nil {
		return nil, err
	}
	return map[string]any{"topic": topic}, nil
}
