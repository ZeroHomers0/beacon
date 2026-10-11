// Package service applies push and receive rules independently of HTTP and SQLite.
package service

import (
	"encoding/hex"
	"strings"
	"time"

	"beacon.local/server/internal/domain"
)

type Repository interface {
	ApplicationRepository
	ServerID() string
	CreateDevice(string) (domain.DeviceCredentials, error)
	AuthenticateToken(string, bool) (string, error)
	SaveMessage(string, string, string, string) (domain.Message, error)
	Messages(string, int64, int, bool) (domain.MessagePage, error)
	Ack(string, []string) error
	CreateBindingCode(string, time.Duration) (domain.BindingCode, error)
	RedeemBindingCode(string, string) (domain.DeviceCredentials, error)
	RevokeDevice(string) error
	RevokeReceiver(string, string) error
	RotateDevice(string) (domain.DeviceCredentials, error)
	ListDevices() ([]domain.DeviceSummary, error)
	StorageStats() (domain.StorageStats, error)
	CleanupConfirmed(int64, bool) (domain.CleanupResult, error)
}

type Service struct {
	repository Repository
	websocket  bool
	pushLimit  *pushAdmission
}

func New(repository Repository) *Service {
	return &Service{repository: repository, pushLimit: &pushAdmission{}}
}

func (s *Service) ServerID() string { return s.repository.ServerID() }

func (s *Service) CreateDevice(name string) (domain.DeviceCredentials, error) {
	return s.repository.CreateDevice(name)
}

func (s *Service) Authenticate(token string, push bool) (string, error) {
	if len(token) != domain.TokenLength {
		return "", domain.ErrUnauthorized
	}
	return s.repository.AuthenticateToken(token, push)
}

func (s *Service) Push(device, title, subtitle, body string) (domain.Message, error) {
	if len(title) > domain.MaxTitleBytes || len(subtitle) > domain.MaxTitleBytes || len(body) == 0 || len(body) > domain.MaxBodyBytes {
		return domain.Message{}, domain.ErrInvalidMessage
	}
	if !s.pushLimit.allow(device, time.Now()) {
		return domain.Message{}, domain.ErrRateLimited
	}
	return s.repository.SaveMessage(device, title, subtitle, body)
}

func (s *Service) Messages(device string, after int64, limit int, pending bool) (domain.MessagePage, error) {
	return s.repository.Messages(device, after, limit, pending)
}

func (s *Service) Ack(device string, ids []string) error { return s.repository.Ack(device, ids) }

func (s *Service) ReceiptStatuses(device string, ids []string) (map[string]string, error) {
	repository, ok := s.repository.(interface {
		ReceiptStatuses(string, []string) (map[string]string, error)
	})
	if !ok {
		return nil, domain.ErrRealtimeUnavailable
	}
	return repository.ReceiptStatuses(device, ids)
}

func (s *Service) CreateBindingCode(name string, ttl time.Duration) (domain.BindingCode, error) {
	name = strings.TrimSpace(name)
	if name == "" || len(name) > domain.MaxDeviceNameBytes || ttl < time.Minute || ttl > 24*time.Hour {
		return domain.BindingCode{}, domain.ErrInvalidBinding
	}
	return s.repository.CreateBindingCode(name, ttl)
}

func (s *Service) Bind(code, name string) (domain.DeviceCredentials, error) {
	code = strings.ToLower(strings.TrimSpace(code))
	name = strings.TrimSpace(name)
	if len(code) != domain.BindingCodeLength || len(name) > domain.MaxDeviceNameBytes {
		return domain.DeviceCredentials{}, domain.ErrInvalidBinding
	}
	if _, err := hex.DecodeString(code); err != nil {
		return domain.DeviceCredentials{}, domain.ErrInvalidBinding
	}
	return s.repository.RedeemBindingCode(code, name)
}

func (s *Service) RevokeDevice(id string) error {
	if len(id) != domain.TokenLength {
		return domain.ErrInvalidDevice
	}
	if _, err := hex.DecodeString(id); err != nil {
		return domain.ErrInvalidDevice
	}
	return s.repository.RevokeDevice(id)
}

// The receive credential is only allowed to revoke its own current device generation.
func (s *Service) RevokeSelf(token, serverID, deviceID string) error {
	if serverID != s.ServerID() || len(token) != domain.TokenLength || len(deviceID) != domain.TokenLength {
		return domain.ErrUnauthorized
	}
	for _, value := range []string{token, deviceID} {
		if value != strings.ToLower(value) {
			return domain.ErrUnauthorized
		}
		if _, err := hex.DecodeString(value); err != nil {
			return domain.ErrUnauthorized
		}
	}
	return s.repository.RevokeReceiver(token, deviceID)
}

func (s *Service) ListDevices() ([]domain.DeviceSummary, error) { return s.repository.ListDevices() }

func (s *Service) RotateDevice(id string) (domain.DeviceCredentials, error) {
	if len(id) != domain.TokenLength {
		return domain.DeviceCredentials{}, domain.ErrInvalidDevice
	}
	if _, err := hex.DecodeString(id); err != nil {
		return domain.DeviceCredentials{}, domain.ErrInvalidDevice
	}
	return s.repository.RotateDevice(id)
}

func (s *Service) StorageStats() (domain.StorageStats, error) {
	return s.repository.StorageStats()
}

func (s *Service) CleanupConfirmed(before int64, apply bool) (domain.CleanupResult, error) {
	if before <= 0 || before > time.Now().UnixMilli() {
		return domain.CleanupResult{}, domain.ErrInvalidCutoff
	}
	return s.repository.CleanupConfirmed(before, apply)
}
