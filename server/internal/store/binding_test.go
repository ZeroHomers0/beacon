package store

import (
	"database/sql"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"beacon.local/server/internal/auth"
)

func TestVersionOneUpgradeKeepsIdentityCredentialsAndMessages(t *testing.T) {
	path := filepath.Join(t.TempDir(), "old.db")
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	for _, query := range []string{
		"CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
		"CREATE TABLE devices(id TEXT PRIMARY KEY,name TEXT NOT NULL,receive_hash TEXT UNIQUE NOT NULL,push_hash TEXT UNIQUE NOT NULL)",
		"CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,device TEXT NOT NULL REFERENCES devices(id),title TEXT NOT NULL,body TEXT NOT NULL,created INTEGER NOT NULL,acked INTEGER NOT NULL DEFAULT 0,subtitle TEXT NOT NULL DEFAULT '')",
		"PRAGMA user_version=1",
	} {
		if _, err = db.Exec(query); err != nil {
			t.Fatal(err)
		}
	}
	if _, err = db.Exec("INSERT INTO metadata VALUES('server_id',?)", "server"); err != nil {
		t.Fatal(err)
	}
	token := strings.Repeat("a", 64)
	if _, err = db.Exec("INSERT INTO devices VALUES('device','phone',?,?)", auth.HashToken(token), "push"); err != nil {
		t.Fatal(err)
	}
	if _, err = db.Exec("INSERT INTO messages(id,device,title,body,created,acked,subtitle) VALUES('old','device','title','body',?,1,'subtitle')", time.Now().UnixMilli()); err != nil {
		t.Fatal(err)
	}
	db.Close()
	repository, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()
	if repository.ServerID() != "server" {
		t.Fatal("server identity changed")
	}
	if id, err := repository.AuthenticateToken(token, false); err != nil || id != "device" {
		t.Fatal("old credential changed")
	}
	page, err := repository.Messages("device", 0, 10, false)
	if err != nil || len(page.Messages) != 1 || page.Messages[0].Subtitle != "subtitle" || page.NextCursor != 1 {
		t.Fatal("history lost")
	}
	var acked, version int
	if err = repository.db.QueryRow("SELECT acked FROM messages WHERE id='old'").Scan(&acked); err != nil || acked != 1 {
		t.Fatal("confirmation lost")
	}
	if err = repository.db.QueryRow("PRAGMA user_version").Scan(&version); err != nil || version != messageSchemaVersion {
		t.Fatal("wrong version")
	}
	code, err := repository.CreateBindingCode("new", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = repository.RedeemBindingCode(code.Code, ""); err != nil {
		t.Fatal(err)
	}
}
