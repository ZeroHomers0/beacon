package store

import (
	"beacon.local/server/internal/auth"
	"beacon.local/server/internal/domain"
	"encoding/json"
	"strconv"
	"time"
)

func (s *Store) TopicPage(device, cursor, snapshot, page string, limit int) (map[string]any, error) {
	if limit < 1 || limit > 100 {
		return nil, domain.ErrInvalidMessage
	}
	now := time.Now().UnixMilli()
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var active int
	if err = tx.QueryRow("SELECT count(*) FROM devices WHERE id=? AND revoked=0", device).Scan(&active); err != nil || active != 1 {
		return nil, domain.ErrUnauthorized
	}
	if _, err = tx.Exec("DELETE FROM topic_snapshots WHERE expires<=?", now); err != nil {
		return nil, err
	}
	var watermark int64
	if err = tx.QueryRow("SELECT coalesce(max(seq),0) FROM topic_changes WHERE device=?", device).Scan(&watermark); err != nil {
		return nil, err
	}
	result := map[string]any{"server_id": s.serverID, "topics": []domain.TopicRecord{}, "has_more": false}
	if cursor != "" {
		if snapshot != "" || page != "" {
			return nil, domain.ErrInvalidMessage
		}
		after, err := s.decodeStateCursor(device+"/topics", cursor)
		if err != nil {
			return nil, err
		}
		var first int64
		if err = tx.QueryRow("SELECT coalesce(min(seq),0) FROM topic_changes WHERE device=?", device).Scan(&first); err != nil {
			return nil, err
		}
		if after > watermark || after < first-1 {
			return nil, ErrStateCursorExpired
		}
		rows, err := tx.Query("SELECT seq,payload FROM topic_changes WHERE device=? AND seq>? ORDER BY seq LIMIT ?", device, after, limit+1)
		if err != nil {
			return nil, err
		}
		items := []domain.TopicRecord{}
		next := after
		more := false
		for rows.Next() {
			var seq int64
			var raw string
			if err = rows.Scan(&seq, &raw); err != nil {
				rows.Close()
				return nil, err
			}
			if len(items) == limit {
				more = true
				break
			}
			var record domain.TopicRecord
			if err = json.Unmarshal([]byte(raw), &record); err != nil {
				rows.Close()
				return nil, err
			}
			items = append(items, record)
			next = seq
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return nil, err
		}
		result["topics"] = items
		result["next_cursor"] = s.stateCursor(device+"/topics", next)
		result["has_more"] = more
	} else {
		var records []domain.TopicRecord
		if snapshot == "" {
			if page != "" {
				return nil, domain.ErrInvalidMessage
			}
			rows, err := tx.Query("SELECT payload FROM topic_catalog WHERE device=? AND deleted=0 ORDER BY application,topic", device)
			if err != nil {
				return nil, err
			}
			records = []domain.TopicRecord{}
			for rows.Next() {
				var raw string
				var record domain.TopicRecord
				if err = rows.Scan(&raw); err != nil {
					rows.Close()
					return nil, err
				}
				if err = json.Unmarshal([]byte(raw), &record); err != nil {
					rows.Close()
					return nil, err
				}
				records = append(records, record)
			}
			err = rows.Err()
			rows.Close()
			if err != nil {
				return nil, err
			}
			snapshot, err = auth.NewToken()
			if err != nil {
				return nil, err
			}
			raw, _ := json.Marshal(records)
			if err = stateStorageCapacity(tx, int64(len(raw))+domain.MaxRequestBytes); err != nil {
				return nil, err
			}
			// At most two bounded snapshots per device; pagination never holds a transaction open.
			if _, err = tx.Exec("DELETE FROM topic_snapshots WHERE device=? AND token NOT IN (SELECT token FROM topic_snapshots WHERE device=? ORDER BY expires DESC LIMIT 1)", device, device); err != nil {
				return nil, err
			}
			if _, err = tx.Exec("INSERT INTO topic_snapshots VALUES(?,?,?,?,?)", snapshot, device, string(raw), watermark, now+600000); err != nil {
				return nil, err
			}
		} else {
			var raw string
			if err = tx.QueryRow("SELECT payload,watermark FROM topic_snapshots WHERE token=? AND device=? AND expires>?", snapshot, device, now).Scan(&raw, &watermark); err != nil {
				return nil, ErrStateCursorExpired
			}
			if err = json.Unmarshal([]byte(raw), &records); err != nil {
				return nil, err
			}
		}
		offset := 0
		if page != "" {
			offset, err = strconv.Atoi(page)
			if err != nil || offset < 0 || offset > len(records) {
				return nil, domain.ErrInvalidMessage
			}
		}
		end := min(offset+limit, len(records))
		result["topics"] = records[offset:end]
		result["snapshot_token"] = snapshot
		result["has_more"] = end < len(records)
		if end < len(records) {
			result["page_cursor"] = strconv.Itoa(end)
		} else {
			result["next_cursor"] = s.stateCursor(device+"/topics", watermark)
		}
	}
	return result, tx.Commit()
}
