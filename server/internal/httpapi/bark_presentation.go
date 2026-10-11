package httpapi

// Bark's iOS-only critical alert and named sounds cannot grant Android privileges.
// Unknown level values degrade to the default priority, so a Bark parameter we
// do not recognise never fails a push. An unusable sound name is dropped
// separately while the extension is validated.
func barkPriority(level, sound string) int {
	priority := 5
	switch level {
	case "passive":
		priority = 2
	case "timeSensitive", "critical":
		priority = 8
	}
	if sound == "none" {
		priority = 2
	}
	return priority
}
