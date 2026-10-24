package store

import (
	"beacon.local/server/internal/auth"
	"beacon.local/server/internal/domain"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"time"
)

var ErrStateCursorExpired = errors.New("state_cursor_expired")

func (s *Store) stateCursor(device string, seq int64) string {
	return base64.RawURLEncoding.EncodeToString([]byte(s.serverID + ":" + device + ":" + strconv.FormatInt(seq, 10)))
}
func (s *Store) decodeStateCursor(device, cursor string) (int64, error) {
	raw, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return 0, ErrStateCursorExpired
	}
	parts := strings.Split(string(raw), ":")
	if len(parts) != 3 || parts[0] != s.serverID || parts[1] != device {
		return 0, ErrStateCursorExpired
	}
	seq, err := strconv.ParseInt(parts[2], 10, 64)
	if err != nil || seq < 0 {
		return 0, ErrStateCursorExpired
	}
	return seq, nil
}

// Expiration creates a synchronized tombstone without modifying the sender's revision.
func expireStates(tx *sql.Tx, device string, now int64) error {
	rows, err := tx.Query("SELECT application,topic,payload FROM current_states WHERE device=? AND deleted=0 AND expires<=?", device, now)
	if err != nil {
		return err
	}
	type expired struct {
		app, topic string
		r          domain.StateRecord
	}
	var items []expired
	for rows.Next() {
		var item expired
		var raw string
		if err = rows.Scan(&item.app, &item.topic, &raw); err != nil {
			rows.Close()
			return err
		}
		if err = json.Unmarshal([]byte(raw), &item.r); err != nil {
			rows.Close()
			return err
		}
		items = append(items, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, item := range items {
		item.r.Deleted = true
		item.r.Operation = "delete"
		item.r.Blocks = nil
		item.r.Body = ""
		item.r.Title = ""
		raw, _ := json.Marshal(item.r)
		if _, err = tx.Exec("UPDATE current_states SET deleted=1,payload=? WHERE device=? AND application=? AND topic=?", string(raw), device, item.app, item.topic); err != nil {
			return err
		}
		if err = appendStateChange(tx, device, item.app, item.topic, string(raw), now); err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) StatePage(device, cursor, snapshot, page string, limit int) (map[string]any, error) {
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
	if err = expireStates(tx, device, now); err != nil {
		return nil, err
	}
	if _, err = tx.Exec("DELETE FROM state_snapshots WHERE expires<=?", now); err != nil {
		return nil, err
	}
	var watermark int64
	if err = tx.QueryRow("SELECT coalesce(max(seq),0) FROM state_changes WHERE device=?", device).Scan(&watermark); err != nil {
		return nil, err
	}
	result := map[string]any{"server_id": s.serverID, "states": []domain.StateRecord{}, "has_more": false}
	if cursor != "" {
		if snapshot != "" || page != "" {
			return nil, domain.ErrInvalidMessage
		}
		after, err := s.decodeStateCursor(device, cursor)
		if err != nil {
			return nil, err
		}
		var first int64
		if err = tx.QueryRow("SELECT coalesce(min(seq),0) FROM state_changes WHERE device=?", device).Scan(&first); err != nil {
			return nil, err
		}
		if after > watermark || after < first-1 {
			return nil, ErrStateCursorExpired
		}
		rows, err := tx.Query("SELECT seq,payload FROM state_changes WHERE device=? AND seq>? ORDER BY seq LIMIT ?", device, after, limit+1)
		if err != nil {
			return nil, err
		}
		items := []domain.StateRecord{}
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
			var record domain.StateRecord
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
		result["states"] = items
		result["next_cursor"] = s.stateCursor(device, next)
		result["has_more"] = more
	} else {
		var records []domain.StateRecord
		if snapshot == "" {
			if page != "" {
				return nil, domain.ErrInvalidMessage
			}
			rows, err := tx.Query("SELECT payload FROM current_states WHERE device=? AND deleted=0 ORDER BY application,topic", device)
			if err != nil {
				return nil, err
			}
			records = []domain.StateRecord{}
			for rows.Next() {
				var raw string
				var record domain.StateRecord
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
			if _, err = tx.Exec("DELETE FROM state_snapshots WHERE device=? AND token NOT IN (SELECT token FROM state_snapshots WHERE device=? ORDER BY expires DESC LIMIT 1)", device, device); err != nil {
				return nil, err
			}
			if _, err = tx.Exec("INSERT INTO state_snapshots VALUES(?,?,?,?,?)", snapshot, device, string(raw), watermark, now+600000); err != nil {
				return nil, err
			}
		} else {
			var raw string
			if err = tx.QueryRow("SELECT payload,watermark FROM state_snapshots WHERE token=? AND device=? AND expires>?", snapshot, device, now).Scan(&raw, &watermark); err != nil {
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
		result["states"] = records[offset:end]
		result["snapshot_token"] = snapshot
		result["has_more"] = end < len(records)
		if end < len(records) {
			result["page_cursor"] = strconv.Itoa(end)
		} else {
			result["next_cursor"] = s.stateCursor(device, watermark)
		}
	}
	return result, tx.Commit()
}
