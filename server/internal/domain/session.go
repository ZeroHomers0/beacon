package domain

// Session identity binds a WebSocket connection to its credential generation.
func ReceiverClientID(device, fingerprint string) string {
	return "beacon-device-" + device + "-" + fingerprint
}
