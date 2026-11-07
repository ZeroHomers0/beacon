package service

import "beacon.local/server/internal/domain"

func (s *Service) ClearMessages(device string, deleteDevice bool) (int64, error) {
	if deleteDevice && !validAdminDevice(device) {
		return 0, domain.ErrInvalidDevice
	}
	return s.repository.ClearMessages(device, deleteDevice)
}

func validAdminDevice(id string) bool { return len(id) == domain.TokenLength }
