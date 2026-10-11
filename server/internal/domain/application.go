package domain

// Sending applications are integration credentials, not separate client sources.
type Application struct {
	ID              string `json:"id"`
	DeviceID        string `json:"device_id"`
	Name            string `json:"name"`
	DefaultPriority int    `json:"default_priority"`
	Revoked         bool   `json:"revoked"`
}

type ApplicationCredential struct {
	Application
	Token string `json:"token"`
}

const DefaultPriority = 5
