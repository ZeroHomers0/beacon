package store

import (
	"errors"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"beacon.local/server/internal/domain"
)

func TestRotationKeepsMessagesAndRejectsOldCredentials(t *testing.T) {
	repository, err := Open(filepath.Join(t.TempDir(), "rotation.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer repository.Close()
	original, err := repository.CreateDevice("phone")
	if err != nil {
		t.Fatal(err)
	}
	other, err := repository.CreateDevice("other")
	if err != nil {
		t.Fatal(err)
	}
	message, err := repository.SaveMessage(original.DeviceID, "title", "subtitle", "body")
	if err != nil {
		t.Fatal(err)
	}
	if err = repository.Ack(original.DeviceID, []string{message.ID}); err != nil {
		t.Fatal(err)
	}
	rotated, err := repository.RotateDevice(original.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	if rotated.DeviceID != original.DeviceID || rotated.ServerID != original.ServerID {
		t.Fatal("rotation changed identity")
	}
	for _, credentials := range []domain.DeviceCredentials{original, other, rotated} {
		for _, push := range []bool{false, true} {
			token := credentials.ReceiveToken
			if push {
				token = credentials.PushKey
			}
			id, err := repository.AuthenticateToken(token, push)
			if credentials == original {
				if err == nil {
					t.Fatal("old credential remained valid")
				}
			} else if err != nil || id != credentials.DeviceID {
				t.Fatal("new or unrelated credential invalid")
			}
		}
	}
	page, err := repository.Messages(rotated.DeviceID, 0, 10, false)
	if err != nil || len(page.Messages) != 1 || !reflect.DeepEqual(page.Messages[0], message) {
		t.Fatal("history or cursor changed")
	}
	pending, err := repository.Messages(rotated.DeviceID, 0, 10, true)
	if err != nil || len(pending.Messages) != 0 {
		t.Fatal("rotation lost acknowledgement")
	}
	var receiveHash, pushHash string
	if err = repository.db.QueryRow("SELECT receive_hash,push_hash FROM devices WHERE id=?", rotated.DeviceID).Scan(&receiveHash, &pushHash); err != nil {
		t.Fatal(err)
	}
	if receiveHash == rotated.ReceiveToken || pushHash == rotated.PushKey {
		t.Fatal("plaintext credential persisted")
	}
	if err = repository.RevokeDevice(rotated.DeviceID); err != nil {
		t.Fatal(err)
	}
	if _, err = repository.RotateDevice(rotated.DeviceID); !errors.Is(err, domain.ErrInvalidDevice) {
		t.Fatal("rotation reactivated revoked device")
	}
	if _, err = repository.RotateDevice(strings.Repeat("a", domain.TokenLength)); !errors.Is(err, domain.ErrInvalidDevice) {
		t.Fatal("unknown device accepted")
	}
}
