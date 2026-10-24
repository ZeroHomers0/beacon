package store

import (
	"beacon.local/server/internal/domain"
	"database/sql"
)

func stateStorageCapacity(tx *sql.Tx, reserve int64) error {
	var pages, free, size int64
	for _, q := range []struct {
		query  string
		target *int64
	}{{"PRAGMA page_count", &pages}, {"PRAGMA freelist_count", &free}, {"PRAGMA page_size", &size}} {
		if err := tx.QueryRow(q.query).Scan(q.target); err != nil {
			return err
		}
	}
	if (pages-free)*size+reserve > domain.MaxLiveDatabaseBytes {
		return domain.ErrStorageCapacity
	}
	return nil
}
