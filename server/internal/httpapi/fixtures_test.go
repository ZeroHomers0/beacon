package httpapi

import (
	"database/sql"
	"net/http"

	"beacon.local/server/internal/domain"
	"beacon.local/server/internal/service"
	"beacon.local/server/internal/store"
)

type wireMessage = domain.Message
type deviceCredentials = domain.DeviceCredentials
type api struct {
	*API
	db         *sql.DB
	repository *store.Store
	serverID   string
}

func openAPI(path string) (*api, error) {
	repository, err := store.Open(path)
	if err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		repository.Close()
		return nil, err
	}
	return &api{API: New(service.New(repository)), db: db, repository: repository, serverID: repository.ServerID()}, nil
}

func (a *api) handler() http.Handler {
	return a.Handler()
}

func (a *api) createDevice(name string) (deviceCredentials, error) {
	return a.service.CreateDevice(name)
}

func (a *api) authenticateToken(token string, push bool) (string, error) {
	return a.service.Authenticate(token, push)
}

func (a *api) close() error { a.db.Close(); return a.repository.Close() }
