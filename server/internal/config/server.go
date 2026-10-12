package config

import "time"

const Version = "1.1.0"

type Config struct {
	Listen       string
	Database     string
	InitDevice   string
	BindingName  string
	BindingTTL   time.Duration
	RevokeDevice string
	RotateDevice string
	ListDevices  bool
	StorageStats bool
	PruneBefore  string
	ApplyCleanup bool
}

const (
	ReadHeaderTimeout = 5 * time.Second
	ReadTimeout       = 15 * time.Second
	WriteTimeout      = 15 * time.Second
	IdleTimeout       = 60 * time.Second
	MaxHeaderBytes    = 16 * 1024
)

const DeliveryInterval = 500 * time.Millisecond
