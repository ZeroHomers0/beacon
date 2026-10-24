package service

import "beacon.local/server/internal/domain"

type stateRepository interface {
	SaveState(string, domain.StatePayload) (domain.StateRecord, error)
	StatePage(string, string, string, string, int) (map[string]any, error)
	StateMeta(string, string) (map[string]any, error)
}

func (s *Service) SaveState(token string, p domain.StatePayload) (domain.StateRecord, error) {
	r, ok := s.repository.(stateRepository)
	if !ok {
		return domain.StateRecord{}, domain.ErrRealtimeUnavailable
	}
	return r.SaveState(token, p)
}
func (s *Service) StatePage(device, cursor, snapshot, page string, limit int) (map[string]any, error) {
	r, ok := s.repository.(stateRepository)
	if !ok {
		return nil, domain.ErrRealtimeUnavailable
	}
	return r.StatePage(device, cursor, snapshot, page, limit)
}
func (s *Service) StateMeta(token, topic string) (map[string]any, error) {
	r, ok := s.repository.(stateRepository)
	if !ok {
		return nil, domain.ErrRealtimeUnavailable
	}
	return r.StateMeta(token, topic)
}

func (s *Service) StateDevice(token string) (string, error) {
	device, err := s.Authenticate(token, true)
	if err == nil {
		return device, nil
	}
	app, err := s.repository.ApplicationForToken(token)
	return app.DeviceID, err
}
