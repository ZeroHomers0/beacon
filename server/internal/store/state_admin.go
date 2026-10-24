package store

import (
	"beacon.local/server/internal/domain"
	"database/sql"
	"encoding/json"
	"time"
	"unicode/utf8"
)

func (s *Store) AdminStates(q domain.StateAdminQuery) (map[string]any, error) {
	if q.Limit == 0 {
		q.Limit = 50
	}
	if q.Limit < 1 || q.Limit > 100 || q.Offset < 0 || q.Offset > 1000000 || utf8.RuneCountInString(q.Search) > 128 {
		return nil, domain.ErrInvalidMessage
	}
	if q.State == "" {
		q.State = "all"
	}
	if q.State != "all" && q.State != "active" && q.State != "stale" && q.State != "expired" && q.State != "deleted" {
		return nil, domain.ErrInvalidMessage
	}
	now := time.Now().UnixMilli()
	classify := `CASE WHEN s.expires>0 AND s.expires<=? THEN 'expired' WHEN s.deleted=1 THEN 'deleted' WHEN coalesce(json_extract(s.payload,'$.stale_at_ms'),0)>0 AND json_extract(s.payload,'$.stale_at_ms')<=? THEN 'stale' ELSE 'active' END`
	where := ` FROM current_states s JOIN devices d ON d.id=s.device LEFT JOIN applications a ON a.id=s.application WHERE (?='' OR s.device=?) AND (?=1 OR s.application=?) AND (?='' OR instr(lower(s.topic||' '||coalesce(json_extract(s.payload,'$.title'),'')||' '||coalesce(a.name,'')||' '||d.name),lower(?))>0) AND (?='all' OR (` + classify + `)=?)`
	app := ""
	all := 1
	if q.Application != nil {
		app = *q.Application
		all = 0
	}
	args := []any{q.Device, q.Device, all, app, q.Search, q.Search, q.State, now, now, q.State}
	var total int
	if err := s.db.QueryRow("SELECT count(*)"+where, args...).Scan(&total); err != nil {
		return nil, err
	}
	selectArgs := append([]any{now, now}, args...)
	selectArgs = append(selectArgs, q.Limit, q.Offset)
	rows, err := s.db.Query(`SELECT s.device,d.name,s.application,coalesce(a.name,''),s.payload,(`+classify+`)`+where+` ORDER BY s.device,s.application,s.topic LIMIT ? OFFSET ?`, selectArgs...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []map[string]any{}
	for rows.Next() {
		var device, name, app, appName, raw, state string
		if err = rows.Scan(&device, &name, &app, &appName, &raw, &state); err != nil {
			return nil, err
		}
		var record domain.StateRecord
		if err = json.Unmarshal([]byte(raw), &record); err != nil {
			return nil, err
		}
		source := appName
		if app == "" {
			source = "默认来源"
		}
		if source == "" {
			source = app
		}
		items = append(items, map[string]any{"device_id": device, "device_name": name, "application_id": app, "source_name": source, "topic_key": record.TopicKey, "title": record.Title, "revision": record.Revision, "state": state, "status": record.Status, "observed_at_ms": record.ObservedAt, "accepted_at_ms": record.AcceptedAt, "expires_at_ms": record.ExpiresAt})
	}
	return map[string]any{"topics": items, "total": total, "has_more": q.Offset+len(items) < total, "next_offset": q.Offset + len(items)}, rows.Err()
}

func (s *Store) AdminState(id domain.StateAdminIdentity) (map[string]any, error) {
	var raw, name string
	err := s.db.QueryRow(`SELECT s.payload,d.name FROM current_states s JOIN devices d ON d.id=s.device WHERE s.device=? AND s.application=? AND s.topic=?`, id.Device, id.Application, id.Topic).Scan(&raw, &name)
	if err == sql.ErrNoRows {
		return nil, domain.ErrInvalidMessage
	}
	if err != nil {
		return nil, err
	}
	var record domain.StateRecord
	if err = json.Unmarshal([]byte(raw), &record); err != nil {
		return nil, err
	}
	return map[string]any{"device_id": id.Device, "device_name": name, "state": record}, nil
}

func (s *Store) ClearAdminState(id domain.StateAdminIdentity) (map[string]any, error) {
	if !domain.ValidTopicKey(id.Topic) || id.Revision < 1 {
		return nil, domain.ErrInvalidMessage
	}
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var raw string
	err = tx.QueryRow(`SELECT payload FROM current_states WHERE device=? AND application=? AND topic=?`, id.Device, id.Application, id.Topic).Scan(&raw)
	if err == sql.ErrNoRows {
		return nil, domain.ErrInvalidMessage
	}
	if err != nil {
		return nil, err
	}
	var r domain.StateRecord
	if err = json.Unmarshal([]byte(raw), &r); err != nil {
		return nil, err
	}
	if r.Revision != id.Revision {
		return nil, domain.ErrStaleStateRevision
	}
	if !r.Deleted {
		r.Deleted = true
		r.Operation = "delete"
		r.Title = ""
		r.Subtitle = ""
		r.Body = ""
		r.Blocks = nil
		r.Actions = nil
		r.ExpiresAt = 0
		r.StaleAt = 0
		data, e := json.Marshal(r)
		if e != nil {
			return nil, e
		}
		if _, err = tx.Exec(`UPDATE current_states SET deleted=1,payload=?,expires=0 WHERE device=? AND application=? AND topic=?`, string(data), id.Device, id.Application, id.Topic); err != nil {
			return nil, err
		}
		if err = appendStateChange(tx, id.Device, id.Application, id.Topic, string(data), time.Now().UnixMilli()); err != nil {
			return nil, err
		}
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return map[string]any{"cleared": true, "revision": r.Revision}, nil
}
