package domain

// OperationalStats is a bounded snapshot, not a per-device log or delivery receipt.
type OperationalStats struct {
	SampledAt int64 `json:"sampled_at_ms"`
	Queued    int64 `json:"queued_realtime_messages"`
	OldestAge int64 `json:"oldest_realtime_age_ms"`
	Retrying  int64 `json:"retrying_realtime_messages"`
}
