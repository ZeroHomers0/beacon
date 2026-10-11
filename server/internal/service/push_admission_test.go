package service

import (
	"fmt"
	"testing"
	"time"
)

func TestPushAdmissionPerDeviceRefillAndBoundedIdentityMap(t *testing.T) {
	var limit pushAdmission
	now := time.Unix(1700000000, 0)
	for i := 0; i < 200; i++ {
		if !limit.allow("one", now) {
			t.Fatal("early limit")
		}
	}
	if limit.allow("one", now) {
		t.Fatal("burst exceeded")
	}
	if !limit.allow("two", now) {
		t.Fatal("cross-device limit")
	}
	if !limit.allow("one", now.Add(time.Second)) {
		t.Fatal("no refill")
	}
	var full pushAdmission
	for i := 0; i < 1024; i++ {
		if !full.allow(fmt.Sprint(i), now.Add(time.Duration(i)*time.Millisecond)) {
			t.Fatal("early map limit", i)
		}
	}
	if full.allow("overflow", now.Add(time.Second)) {
		t.Fatal("unbounded map")
	}
	if len(full.devices) != 1024 {
		t.Fatal("map size")
	}
	if !full.allow("new", now.Add(3*time.Minute)) {
		t.Fatal("idle entries did not retire")
	}
}
