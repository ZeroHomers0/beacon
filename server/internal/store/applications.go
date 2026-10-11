package store

import (
	"strings"

	"beacon.local/server/internal/auth"
	"beacon.local/server/internal/domain"
)

func (a *Store) CreateApplication(device, name string, priority int) (domain.ApplicationCredential, error) {
	name = strings.TrimSpace(name)
	if name == "" || len(name) > domain.MaxDeviceNameBytes || priority < 0 || priority > 10 {
		return domain.ApplicationCredential{}, domain.ErrInvalidMessage
	}
	id, err := auth.NewToken()
	if err != nil {
		return domain.ApplicationCredential{}, err
	}
	token, err := auth.NewToken()
	if err != nil {
		return domain.ApplicationCredential{}, err
	}
	result, err := a.db.Exec(`INSERT INTO applications(id,device,name,token_hash,default_priority) SELECT ?,id,?,?,? FROM devices WHERE id=? AND revoked=0`, id, name, auth.HashToken(token), priority, device)
	if err != nil {
		return domain.ApplicationCredential{}, err
	}
	count, err := result.RowsAffected()
	if err != nil || count != 1 {
		return domain.ApplicationCredential{}, domain.ErrInvalidDevice
	}
	return domain.ApplicationCredential{Application: domain.Application{ID: id, DeviceID: device, Name: name, DefaultPriority: priority}, Token: token}, nil
}

func (a *Store) ListApplications() ([]domain.Application, error) {
	rows, err := a.db.Query(`SELECT a.id,a.device,a.name,a.default_priority,(a.revoked OR d.revoked) FROM applications a JOIN devices d ON d.id=a.device ORDER BY a.name,a.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []domain.Application{}
	for rows.Next() {
		var item domain.Application
		if err := rows.Scan(&item.ID, &item.DeviceID, &item.Name, &item.DefaultPriority, &item.Revoked); err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}

func (a *Store) RevokeApplication(id string) error {
	result, err := a.db.Exec("UPDATE applications SET revoked=1 WHERE id=?", id)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil || count != 1 {
		return domain.ErrInvalidDevice
	}
	return nil
}

func (a *Store) ApplicationForToken(token string) (domain.Application, error) {
	var item domain.Application
	if len(token) != domain.TokenLength {
		return item, domain.ErrUnauthorized
	}
	err := a.db.QueryRow(`SELECT a.id,a.device,a.name,a.default_priority FROM applications a JOIN devices d ON d.id=a.device WHERE a.token_hash=? AND a.revoked=0 AND d.revoked=0`, auth.HashToken(token)).Scan(&item.ID, &item.DeviceID, &item.Name, &item.DefaultPriority)
	if err != nil {
		return item, domain.ErrUnauthorized
	}
	return item, nil
}
