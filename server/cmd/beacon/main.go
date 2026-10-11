package main

import (
	"encoding/json"
	"flag"
	"log"
	"os"
	"time"

	"beacon.local/server/internal/config"
	"beacon.local/server/internal/service"
	"beacon.local/server/internal/store"
)

func main() {
	var settings config.Config
	flag.StringVar(&settings.Listen, "listen", "0.0.0.0:8080", "HTTP listen address")
	flag.StringVar(&settings.Database, "db", "beacon.db", "SQLite database path")
	flag.StringVar(&settings.InitDevice, "init-device", "", "Create a device locally and print credentials once, then exit")
	flag.StringVar(&settings.BindingName, "create-binding", "", "Create a one-time binding code for this device name, then exit")
	flag.DurationVar(&settings.BindingTTL, "binding-ttl", 10*time.Minute, "Binding code lifetime (1m to 24h)")
	flag.StringVar(&settings.RevokeDevice, "revoke-device", "", "Revoke a device ID and both of its credentials, then exit")
	flag.StringVar(&settings.RotateDevice, "rotate-device", "", "Replace both credentials of an active device while keeping its messages, then exit")
	flag.BoolVar(&settings.ListDevices, "list-devices", false, "List device IDs, names and revocation status without secrets, then exit")
	flag.BoolVar(&settings.StorageStats, "storage-stats", false, "Show message counts and SQLite page allocation without message contents, then exit")
	flag.StringVar(&settings.PruneBefore, "prune-before", "", "Preview confirmed messages created before an RFC3339 timestamp, then exit")
	flag.BoolVar(&settings.ApplyCleanup, "apply", false, "Execute the cleanup requested by -prune-before; otherwise only preview")
	flag.Parse()
	if flag.NArg() != 0 || (settings.ApplyCleanup && settings.PruneBefore == "") {
		log.Fatal("Unexpected arguments; -apply requires -prune-before")
	}
	commands := 0
	if settings.ListDevices {
		commands++
	}
	if settings.StorageStats {
		commands++
	}
	for _, value := range []string{settings.InitDevice, settings.BindingName, settings.RevokeDevice, settings.RotateDevice, settings.PruneBefore} {
		if value != "" {
			commands++
		}
	}
	if commands > 1 {
		log.Fatal("Choose only one administration command")
	}
	var cutoff time.Time
	if settings.PruneBefore != "" {
		var err error
		cutoff, err = time.Parse(time.RFC3339, settings.PruneBefore)
		if err != nil || cutoff.UnixMilli() <= 0 || cutoff.After(time.Now()) {
			log.Fatal("Cleanup cutoff must be a past RFC3339 timestamp with timezone")
		}
	}
	repository, err := store.Open(settings.Database)
	if err != nil {
		log.Fatal("Cannot open server database")
	}
	defer repository.Close()
	app := service.New(repository)
	if settings.StorageStats {
		stats, err := app.StorageStats()
		if err != nil {
			log.Fatal("Cannot read storage statistics")
		}
		if err = json.NewEncoder(os.Stdout).Encode(stats); err != nil {
			log.Fatal("Cannot export storage statistics")
		}
		return
	}
	if settings.PruneBefore != "" {
		result, err := app.CleanupConfirmed(cutoff.UnixMilli(), settings.ApplyCleanup)
		if err != nil {
			log.Fatal("Cannot clean confirmed history")
		}
		if err = json.NewEncoder(os.Stdout).Encode(result); err != nil {
			log.Fatal("Cannot export cleanup result; inspect statistics before retrying")
		}
		return
	}
	if settings.RotateDevice != "" {
		credentials, err := app.RotateDevice(settings.RotateDevice)
		if err != nil {
			log.Fatal("Cannot rotate credentials; device must exist and be active")
		}
		if err = json.NewEncoder(os.Stdout).Encode(credentials); err != nil {
			log.Fatal("Cannot export credentials; retry rotation to replace the inaccessible credentials")
		}
		return
	}
	if settings.ListDevices {
		devices, err := app.ListDevices()
		if err != nil {
			log.Fatal("Cannot list devices")
		}
		if err = json.NewEncoder(os.Stdout).Encode(devices); err != nil {
			log.Fatal("Cannot export devices")
		}
		return
	}
	if settings.BindingName != "" {
		code, err := app.CreateBindingCode(settings.BindingName, settings.BindingTTL)
		if err != nil {
			log.Fatal("Cannot create binding code; name and lifetime must be valid")
		}
		if err = json.NewEncoder(os.Stdout).Encode(code); err != nil {
			log.Fatal("Cannot export binding code")
		}
		return
	}
	if settings.RevokeDevice != "" {
		if err = app.RevokeDevice(settings.RevokeDevice); err != nil {
			log.Fatal("Cannot revoke device")
		}
		if err = json.NewEncoder(os.Stdout).Encode(map[string]bool{"revoked": true}); err != nil {
			log.Fatal("Cannot export result")
		}
		return
	}
	if settings.InitDevice != "" {
		credentials, err := app.CreateDevice(settings.InitDevice)
		if err != nil {
			log.Fatal("Cannot create device")
		}
		if err := json.NewEncoder(os.Stdout).Encode(credentials); err != nil {
			log.Fatal("Cannot export device credentials")
		}
		return
	}
	if err := serve(settings, app, repository); err != nil {
		log.Fatal(err)
	}
}
