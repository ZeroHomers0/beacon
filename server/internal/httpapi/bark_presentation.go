package httpapi

import (
	"errors"
	"unicode/utf8"
)

// Bark's iOS-only critical alert and named sounds cannot grant Android privileges.
// Unknown sound names use the user's channel sound; "none" requests a silent channel.
func barkPriority(level, sound string) (int, error) {
	if len(sound) > 128 || !utf8.ValidString(sound) {
		return 0, errors.New("invalid sound")
	}
	priority := 5
	switch level {
	case "", "active":
	case "passive":
		priority = 2
	case "timeSensitive", "critical":
		priority = 8
	default:
		return 0, errors.New("invalid level")
	}
	if sound == "none" {
		priority = 2
	}
	return priority, nil
}
