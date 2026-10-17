package domain

const RealtimeProtocolVersion = 2

// RealtimeEnvelopeProtocol versions the per-message frame on the stream. It is
// independent of both the HTTP contract and the realtime configuration contract,
// so a frame shape change does not force a reconnect negotiation change.
const RealtimeEnvelopeProtocol = 1

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
