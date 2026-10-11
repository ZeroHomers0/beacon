package service

import (
	"beacon.local/server/internal/domain"
	"time"
)

type ApplicationRepository interface {
	CreateApplication(string, string, int) (domain.ApplicationCredential, error)
	ListApplications() ([]domain.Application, error)
	RevokeApplication(string) error
	ApplicationForToken(string) (domain.Application, error)
	SavePriorityMessage(string, string, string, string, int, string, string, ...string) (domain.Message, error)
}

func (s *Service) CreateApplication(device, name string, priority int) (domain.ApplicationCredential, error) {
	return s.repository.CreateApplication(device, name, priority)
}
func (s *Service) ListApplications() ([]domain.Application, error) {
	return s.repository.ListApplications()
}
func (s *Service) RevokeApplication(id string) error { return s.repository.RevokeApplication(id) }

// Legacy device push keys remain valid. Application credentials are send-only.
func (s *Service) PushCredential(token, title, subtitle, body string, priority *int, extensions ...string) (domain.Message, error) {
	repository := s.repository
	device, err := s.Authenticate(token, true)
	applicationID := ""
	selected := domain.DefaultPriority
	if err != nil {
		application, err := repository.ApplicationForToken(token)
		if err != nil {
			return domain.Message{}, domain.ErrUnauthorized
		}
		device, applicationID, selected = application.DeviceID, application.ID, application.DefaultPriority
	}
	if priority != nil {
		selected = *priority
	}
	if !s.pushLimit.allow(device, time.Now()) {
		return domain.Message{}, domain.ErrRateLimited
	}
	return repository.SavePriorityMessage(device, title, subtitle, body, selected, applicationID, token, extensions...)
}
