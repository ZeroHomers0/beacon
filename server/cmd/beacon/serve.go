package main

import (
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"beacon.local/server/internal/config"
	"beacon.local/server/internal/httpapi"
	"beacon.local/server/internal/service"
	"beacon.local/server/internal/store"
)

func serve(settings config.Config, app *service.Service, repository *store.Store) error {
	retention, err := config.RetentionFromEnv()
	if err != nil {
		return errors.New("invalid history retention configuration")
	}
	app = app.WithWebSocket()
	adminToken, err := config.WebAdminTokenFromEnv()
	if err != nil {
		return errors.New("invalid web administration configuration")
	}
	hub := httpapi.NewStreamHub(app)
	defer hub.Close()
	listener, err := net.Listen("tcp", settings.Listen)
	if err != nil {
		return errors.New("cannot open HTTP listener")
	}
	defer listener.Close()
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	var workers sync.WaitGroup
	if retention > 0 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			service.RunHistoryRetention(ctx, repository, retention, config.RetentionInterval, config.RetentionTimeout, config.RetentionBatchSize, func(deleted int64, err error) {
				if err != nil {
					log.Print("history cleanup deferred; transaction rolled back")
					return
				}
				log.Printf("history cleanup: confirmed_deleted=%d", deleted)
			})
		}()
		log.Printf("confirmed history retention enabled: days=%d batch=%d", int(retention/(24*time.Hour)), config.RetentionBatchSize)
	}
	workers.Add(3)
	go func() {
		defer workers.Done()
		service.RunDeliveryExpiration(ctx, repository, func(error) { log.Print("delivery expiration deferred; transaction rolled back") })
	}()
	go func() {
		defer workers.Done()
		service.RunDisconnect(ctx, repository, hub, config.DeliveryInterval, func(error) {
			log.Print("stream disconnect deferred")
		})
	}()
	go func() {
		defer workers.Done()
		service.RunDelivery(ctx, repository, hub, config.DeliveryInterval, nil)
	}()
	server := &http.Server{Handler: httpapi.New(app).WithStream(hub).WithWebAdmin(adminToken).Handler(), ReadHeaderTimeout: config.ReadHeaderTimeout,
		ReadTimeout: config.ReadTimeout, WriteTimeout: config.WriteTimeout, IdleTimeout: config.IdleTimeout, MaxHeaderBytes: config.MaxHeaderBytes}
	shutdownDone := make(chan struct{})
	go func() {
		defer close(shutdownDone)
		<-ctx.Done()
		deadline, done := context.WithTimeout(context.Background(), 5*time.Second)
		defer done()
		if server.Shutdown(deadline) != nil {
			server.Close()
		}
		hub.Close()
	}()
	log.Printf("Beacon server listening on %s", settings.Listen)
	err = server.Serve(listener)
	cancel()
	workers.Wait()
	<-shutdownDone
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return errors.New("HTTP server stopped unexpectedly")
}
