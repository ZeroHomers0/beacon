package domain

const RealtimeProtocolVersion = 2

// The receive token remains local and is sent only as an HTTPS authorization header.
type RealtimeConfiguration struct {
	Protocol   int    `json:"protocol"`
	ServerID   string `json:"server_id"`
	DeviceID   string `json:"device_id"`
	StreamPath string `json:"stream_path"`
}

type RealtimeEnvelope struct {
	Protocol int     `json:"protocol"`
	ServerID string  `json:"server_id"`
	Message  Message `json:"message"`
}
