package domain

import "encoding/json"

type StatePayload struct {
	DigestInput   json.RawMessage  `json:"-"`
	Mode          string           `json:"mode"`
	SchemaVersion int              `json:"schema_version"`
	Operation     string           `json:"operation,omitempty"`
	TopicKey      string           `json:"topic_key"`
	Revision      int64            `json:"revision"`
	ObservedAt    int64            `json:"observed_at_ms,omitempty"`
	FreshFor      int64            `json:"fresh_for_seconds,omitempty"`
	StateType     string           `json:"state_type,omitempty"`
	Status        string           `json:"status,omitempty"`
	Title         string           `json:"title,omitempty"`
	Subtitle      string           `json:"subtitle,omitempty"`
	Body          string           `json:"body,omitempty"`
	Priority      *int             `json:"priority,omitempty"`
	Group         string           `json:"group,omitempty"`
	TTL           int64            `json:"ttl,omitempty"`
	Actions       MessageActions   `json:"actions,omitempty"`
	Blocks        StructuredBlocks `json:"blocks,omitempty"`
}

func (p StatePayload) Valid(now int64) bool {
	if p.Mode != "state" || p.SchemaVersion != 1 || !ValidTopicKey(p.TopicKey) || p.Revision < 1 || p.Revision > MaxStateRevision {
		return false
	}
	if p.Operation == "delete" {
		return p.Title == "" && p.Subtitle == "" && p.Body == "" && p.ObservedAt == 0 && p.FreshFor == 0 && p.StateType == "" && p.Status == "" && p.Priority == nil && p.Group == "" && p.TTL == 0 && len(p.Actions) == 0 && len(p.Blocks) == 0
	}
	if p.Operation != "" && p.Operation != "upsert" {
		return false
	}
	if p.StateType != "" && p.StateType != "persistent" && p.StateType != "task" {
		return false
	}
	if !ValidStateStatus(p.StateType == "task", p.Status) || p.ObservedAt < 0 || p.ObservedAt > now+300000 {
		return false
	}
	ttl := p.TTL
	if ttl == 0 {
		ttl = 604800
	}
	return p.FreshFor >= 0 && p.FreshFor <= ttl && ValidMessageText(p.Title, p.Subtitle, p.Body) && (p.Priority == nil || *p.Priority >= 0 && *p.Priority <= 10) && (PushOptions{SchemaVersion: 1, Blocks: p.Blocks, Actions: p.Actions, Group: p.Group, TTL: p.TTL}).Valid()
}

type StateRecord struct {
	StatePayload
	ApplicationID   string `json:"application_id"`
	ApplicationName string `json:"application_name"`
	AcceptedAt      int64  `json:"accepted_at_ms"`
	ExpiresAt       int64  `json:"expires_at_ms"`
	StaleAt         int64  `json:"stale_at_ms"`
	Deleted         bool   `json:"deleted"`
	Unchanged       bool   `json:"unchanged"`
}
