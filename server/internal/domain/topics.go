package domain

import "errors"

var ErrTopicNotFound = errors.New("topic_not_found")
var ErrTopicDeleted = errors.New("topic_deleted")
var ErrTopicConflict = errors.New("topic_config_conflict")

type TopicRecord struct {
	Device          string `json:"device_id"`
	Application     string `json:"application_id"`
	Topic           string `json:"topic_key"`
	Name            string `json:"name"`
	ApplicationName string `json:"application_name"`
	Version         int64  `json:"config_version"`
	Created         int64  `json:"created_at_ms"`
	Updated         int64  `json:"updated_at_ms"`
	Deleted         bool   `json:"deleted"`
}
type TopicMutation struct {
	Device      string `json:"device_id"`
	Application string `json:"application_id"`
	Topic       string `json:"topic_key"`
	Name        string `json:"name"`
	Version     int64  `json:"config_version"`
	RequestID   string `json:"request_id"`
}
