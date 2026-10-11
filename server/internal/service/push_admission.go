package service

import (
	"sync"
	"time"
)

// Authenticated device IDs only; bounded memory independent of proxy headers or credentials.
type pushAdmission struct {
	mu      sync.Mutex
	devices map[string]pushBucket
	global  pushBucket
}
type pushBucket struct {
	at     time.Time
	tokens float64
}

func refill(bucket pushBucket, now time.Time, burst, rate float64) pushBucket {
	if bucket.at.IsZero() {
		return pushBucket{now, burst}
	}
	if now.After(bucket.at) {
		bucket.tokens = min(burst, bucket.tokens+now.Sub(bucket.at).Seconds()*rate)
		bucket.at = now
	}
	return bucket
}

func (a *pushAdmission) allow(device string, now time.Time) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.global = refill(a.global, now, 1000, 100)
	if a.global.tokens < 1 {
		return false
	}
	if a.devices == nil {
		a.devices = make(map[string]pushBucket)
	}
	bucket, exists := a.devices[device]
	if !exists && len(a.devices) >= 1024 {
		for key, value := range a.devices {
			if now.Sub(value.at) >= 2*time.Minute {
				delete(a.devices, key)
			}
		}
		if len(a.devices) >= 1024 {
			return false
		}
	}
	bucket = refill(bucket, now, 200, 10)
	if bucket.tokens < 1 {
		a.devices[device] = bucket
		return false
	}
	bucket.tokens--
	a.global.tokens--
	a.devices[device] = bucket
	return true
}
