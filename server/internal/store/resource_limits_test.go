package store

import (
	"context"
	"errors"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"beacon.local/server/internal/domain"
)

func TestResourceLimitsAndBindingExpiration(t *testing.T) {
	repository, err := Open(filepath.Join(t.TempDir(), "limits.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()
	if _, err = repository.CreateDevice(strings.Repeat("a", 129)); !errors.Is(err, domain.ErrInvalidDevice) {
		t.Fatalf("invalid name accepted: %v", err)
	}
	var pageSize, maxPages int64
	if err = repository.db.QueryRow("PRAGMA page_size").Scan(&pageSize); err != nil {
		t.Fatal(err)
	}
	if err = repository.db.QueryRow("PRAGMA max_page_count").Scan(&maxPages); err != nil {
		t.Fatal(err)
	}
	if pageSize*maxPages != 2*domain.MaxLiveDatabaseBytes {
		t.Fatal("database file limit not applied")
	}
	if _, err = repository.CreateBindingCode("phone", time.Minute); err != nil {
		t.Fatal(err)
	}
	if _, err = repository.db.Exec("UPDATE binding_codes SET expires_at=1"); err != nil {
		t.Fatal(err)
	}
	if _, err = repository.ExpireDeliveryBatch(context.Background(), time.Now().UnixMilli()); err != nil {
		t.Fatal(err)
	}
	var count int
	if err = repository.db.QueryRow("SELECT count(*) FROM binding_codes").Scan(&count); err != nil || count != 0 {
		t.Fatalf("expired bindings remain: %d, %v", count, err)
	}
}
