// Package domain defines the stable HTTP model and business limits.
package domain

import (
	"errors"
	"time"
)

const PendingLifetime = 7 * 24 * time.Hour
const ReceiptLifetime = 30 * 24 * time.Hour
const ReceiptGrace = 30 * time.Minute

const (
	TokenLength              = 64
	MaxTitleBytes            = 1024
	MaxBodyBytes             = 65536
	MaxPageSize              = 100
	DefaultPageSize          = 25
	MaxRequestBytes          = 512 * 1024
	ProtocolVersion          = 1
	BindingCodeLength        = 32
	MaxDeviceNameBytes       = 128
	MaxRetentionBatchSize    = 100
	MaxPendingPerDevice      = 10000
	MaxPendingBytesPerDevice = 64 * 1024 * 1024
	MaxStoredMessages        = 100000
	MaxLiveDatabaseBytes     = 256 * 1024 * 1024
)

var (
	ErrUnauthorized        = errors.New("unauthorized")
	ErrInvalidMessage      = errors.New("invalid_message")
	ErrUnknownMessage      = errors.New("unknown_message")
	ErrInvalidBinding      = errors.New("invalid_or_expired_binding")
	ErrInvalidDevice       = errors.New("invalid_device")
	ErrInvalidCutoff       = errors.New("invalid_cleanup_cutoff")
	ErrInvalidDelivery     = errors.New("invalid_delivery_state")
	ErrRealtimeUnavailable = errors.New("realtime_unavailable")
	ErrStorageCapacity     = errors.New("storage_capacity_reached")
	ErrRateLimited         = errors.New("push_rate_limited")
)

type StorageStats struct {
	Messages          int64 `json:"messages"`
	Pending           int64 `json:"pending"`
	Confirmed         int64 `json:"confirmed"`
	Devices           int64 `json:"devices"`
	Receipts          int64 `json:"cleanup_confirmation_receipts"`
	DatabaseBytes     int64 `json:"database_allocated_bytes"`
	ReusableBytes     int64 `json:"database_reusable_bytes"`
	QueuedRealtime    int64 `json:"queued_realtime_messages"`
	QueuedDisconnects int64 `json:"queued_session_disconnects"`
	OldestPendingMS   int64 `json:"oldest_pending_created_ms"`
	OldestConfirmedMS int64 `json:"oldest_confirmed_created_ms"`
}

type CleanupResult struct {
	BeforeMS         int64 `json:"before_ms"`
	Eligible         int64 `json:"eligible_confirmed"`
	ProtectedPending int64 `json:"protected_pending"`
	Deleted          int64 `json:"deleted"`
	Applied          bool  `json:"applied"`
}

type BindingCode struct {
	Code       string `json:"binding_code"`
	ExpiresAt  int64  `json:"expires_at_ms"`
	DeviceName string `json:"device_name"`
}

type DeviceSummary struct {
	DeviceID string `json:"device_id"`
	Name     string `json:"name"`
	Revoked  bool   `json:"revoked"`
}

type DeviceCredentials struct {
	DeviceID     string `json:"device_id"`
	ServerID     string `json:"server_id"`
	ReceiveToken string `json:"receive_token"`
	PushKey      string `json:"push_key"`
}

type PushOptions struct {
	Key   string `json:"-"`
	Group string `json:"group"`
	TTL   int64  `json:"ttl"`
}

var ErrIdempotencyConflict = errors.New("idempotency_conflict")

func (o PushOptions) Valid() bool {
	if len(o.Key) > 128 || len(o.Group) > 128 || o.TTL < 0 || o.TTL > 604800 {
		return false
	}
	for _, c := range o.Key {
		if c < 33 || c > 126 {
			return false
		}
	}
	return true
}

type Message struct {
	ApplicationName string `json:"application_name,omitempty"`
	Group           string `json:"group,omitempty"`
	ExpiresAt       int64  `json:"expires_at_ms"`
	Bark            string `json:"bark,omitempty"`
	ID              string `json:"id"`
	Sequence        int64  `json:"sequence"`
	Title           string `json:"title"`
	Body            string `json:"body"`
	CreatedAt       int64  `json:"created_at_ms"`
	Subtitle        string `json:"subtitle"`
	Priority        int    `json:"priority"`
	ApplicationID   string `json:"application_id,omitempty"`
}

type MessagePage struct {
	ServerID   string    `json:"server_id"`
	Messages   []Message `json:"messages"`
	NextCursor int64     `json:"next_cursor"`
	HasMore    bool      `json:"has_more"`
}
