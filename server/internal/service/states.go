package service

import "beacon.local/server/internal/domain"

type StateAdminRepository interface {
	AdminStates(domain.StateAdminQuery) (map[string]any, error)
	AdminState(domain.StateAdminIdentity) (map[string]any, error)
	ClearAdminState(domain.StateAdminIdentity) (map[string]any, error)
}

func (s *Service) AdminStates(q domain.StateAdminQuery) (map[string]any, error) {
	return s.repository.AdminStates(q)
}
func (s *Service) AdminState(id domain.StateAdminIdentity) (map[string]any, error) {
	return s.repository.AdminState(id)
}
func (s *Service) ClearAdminState(id domain.StateAdminIdentity) (map[string]any, error) {
	return s.repository.ClearAdminState(id)
}

type StateRepository interface {
	SaveState(string, domain.StatePayload) (domain.StateRecord, error)
	StatePage(string, string, string, string, int) (map[string]any, error)
	StateMeta(string, string) (map[string]any, error)
}

func (s *Service) SaveState(token string, p domain.StatePayload) (domain.StateRecord, error) {
	return s.repository.SaveState(token, p)
}
func (s *Service) StatePage(device, cursor, snapshot, page string, limit int) (map[string]any, error) {
	return s.repository.StatePage(device, cursor, snapshot, page, limit)
}
func (s *Service) StateMeta(token, topic string) (map[string]any, error) {
	return s.repository.StateMeta(token, topic)
}

func (s *Service) StateDevice(token string) (string, error) {
	device, err := s.Authenticate(token, true)
	if err == nil {
		return device, nil
	}
	app, err := s.repository.ApplicationForToken(token)
	return app.DeviceID, err
}

type TopicRepository interface {
	MutateTopic(string, domain.TopicMutation) (map[string]any, error)
	AdminTopics(domain.StateAdminQuery) (map[string]any, error)
	AdminTopic(domain.TopicMutation) (map[string]any, error)
	TopicPage(string, string, string, string, int) (map[string]any, error)
}

func (s *Service) MutateTopic(a string, p domain.TopicMutation) (map[string]any, error) {
	return s.repository.MutateTopic(a, p)
}
func (s *Service) AdminTopics(q domain.StateAdminQuery) (map[string]any, error) {
	return s.repository.AdminTopics(q)
}
func (s *Service) AdminTopic(q domain.TopicMutation) (map[string]any, error) {
	return s.repository.AdminTopic(q)
}
func (s *Service) TopicPage(d, c, t, p string, l int) (map[string]any, error) {
	return s.repository.TopicPage(d, c, t, p, l)
}
