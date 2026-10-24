package domain

type StateAdminQuery struct {
	Device      string  `json:"device_id"`
	Application *string `json:"application_id"`
	Search      string  `json:"search"`
	State       string  `json:"state"`
	Limit       int     `json:"limit"`
	Offset      int     `json:"offset"`
}

type StateAdminIdentity struct {
	Device      string `json:"device_id"`
	Application string `json:"application_id"`
	Topic       string `json:"topic_key"`
	Revision    int64  `json:"revision"`
}
