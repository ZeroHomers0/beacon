package service

import "beacon.local/server/internal/domain"

func (s *Service) WithWebSocket() *Service {
	copy := *s
	copy.websocket = true
	return &copy
}

func (s *Service) RealtimeConfiguration(device, token string) (domain.RealtimeConfiguration, error) {
	id, err := s.Authenticate(token, false)
	if err != nil || id != device {
		return domain.RealtimeConfiguration{}, domain.ErrUnauthorized
	}
	if !s.websocket {
		return domain.RealtimeConfiguration{}, domain.ErrRealtimeUnavailable
	}
	return domain.RealtimeConfiguration{
		Protocol:   domain.RealtimeProtocolVersion,
		ServerID:   s.ServerID(),
		DeviceID:   device,
		StreamPath: "/api/v1/realtime/stream",
	}, nil
}
