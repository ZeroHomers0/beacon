package domain

import "time"

// Repeat disconnects through the bounded in-flight handshake margin.
// This also covers an authentication response that races with credential rotation.
const SessionDisconnectGuard = 90 * time.Second
const SessionDisconnectRepeat = 30 * time.Second

type DisconnectTask struct {
	ClientID   string
	LeaseToken string
	LeaseUntil int64
}
