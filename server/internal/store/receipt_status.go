package store

import "time"

// Read-only availability is distinct from an acknowledgement. Missing IDs do not become confirmed.
func (a *Store) ReceiptStatuses(device string, ids []string) (map[string]string, error) {
	result := make(map[string]string, len(ids))
	tx, err := a.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	now := time.Now().UnixMilli()
	for _, id := range ids {
		var available int
		err = tx.QueryRow(`SELECT count(*) FROM (
            SELECT id FROM messages WHERE device=? AND id=?
            UNION SELECT id FROM confirmed_receipts WHERE device=? AND id=? AND (expires_at=0 OR expires_at>?)
            UNION SELECT id FROM expired_receipts WHERE device=? AND id=? AND expires_at>?)`, device, id, device, id, now, device, id, now).Scan(&available)
		if err != nil {
			return nil, err
		}
		result[id] = "missing"
		if available > 0 {
			result[id] = "available"
		}
	}
	return result, tx.Commit()
}
