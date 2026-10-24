package domain

import (
	"bytes"
	"encoding/json"
	"math"
	"unicode/utf8"
)

// StructuredBlock is a bounded numeric block. Further block kinds are added only
// with matching validation in both backends and client rendering support.
var BlockKinds = []string{"progress", "metric", "kv", "list", "countdown", "timeline", "pipeline", "alert", "sparkline", "segments"}

type BlockItem struct {
	ID     string   `json:"id"`
	Label  string   `json:"label,omitempty"`
	Text   string   `json:"text,omitempty"`
	Tone   string   `json:"tone,omitempty"`
	Status string   `json:"status,omitempty"`
	At     *int64   `json:"at_ms,omitempty"`
	Value  *float64 `json:"value,omitempty"`
}
type BlockPoint struct {
	At    int64   `json:"at_ms"`
	Value float64 `json:"value"`
}
type BlockBand struct {
	Until float64 `json:"until"`
	Tone  string  `json:"tone"`
}

type StructuredBlock struct {
	Items         []BlockItem  `json:"items,omitempty"`
	Points        []BlockPoint `json:"points,omitempty"`
	Bands         []BlockBand  `json:"bands,omitempty"`
	Text          string       `json:"text,omitempty"`
	TargetAt      *int64       `json:"target_at_ms,omitempty"`
	Total         *float64     `json:"total,omitempty"`
	Kind          string       `json:"kind"`
	ID            string       `json:"id"`
	Label         string       `json:"label,omitempty"`
	Value         *float64     `json:"value,omitempty"`
	Min           *float64     `json:"min,omitempty"`
	Max           *float64     `json:"max,omitempty"`
	Unit          string       `json:"unit,omitempty"`
	Tone          string       `json:"tone,omitempty"`
	Trend         string       `json:"trend,omitempty"`
	Indeterminate bool         `json:"indeterminate,omitempty"`
}

type StructuredBlocks []StructuredBlock

func (b *StructuredBlocks) UnmarshalJSON(raw []byte) error {
	if bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		return ErrInvalidMessage
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	var blocks []StructuredBlock
	if err := decoder.Decode(&blocks); err != nil {
		return err
	}
	if !ValidStructuredBlocks(1, blocks) {
		return ErrInvalidMessage
	}
	*b = blocks
	return nil
}

func ValidStructuredBlocks(version int, blocks []StructuredBlock) bool {
	if len(blocks) == 0 {
		return version == 0 || version == 1
	}
	if version != 1 || len(blocks) > 16 {
		return false
	}
	raw, err := json.Marshal(blocks)
	if err != nil || len(raw) > 32768 {
		return false
	}
	ids := map[string]bool{}
	for _, b := range blocks {
		if !ValidTopicKey(b.ID) || len(b.ID) > 64 || ids[b.ID] || len(b.Label) > 128 || len(b.Unit) > 32 || !utf8.ValidString(b.Label) || !utf8.ValidString(b.Unit) {
			return false
		}
		ids[b.ID] = true
		if b.Tone != "" && b.Tone != "normal" && b.Tone != "warning" && b.Tone != "critical" {
			return false
		}
		for _, n := range []*float64{b.Value, b.Min, b.Max} {
			if n != nil && (math.IsNaN(*n) || math.IsInf(*n, 0) || math.Abs(*n) > 1e15) {
				return false
			}
		}
		if b.Kind != "progress" && b.Kind != "metric" {
			if !validRichBlock(b) {
				return false
			}
			continue
		}
		if len(b.Items) > 0 || len(b.Points) > 0 || b.Text != "" || b.TargetAt != nil || b.Total != nil {
			return false
		}
		if b.Indeterminate {
			if b.Kind != "progress" || b.Value != nil || b.Min != nil || b.Max != nil || b.Trend != "" {
				return false
			}
			continue
		}
		if b.Value == nil {
			return false
		}
		if b.Kind == "progress" {
			if b.Max == nil || b.Trend != "" {
				return false
			}
		} else {
			if (b.Min == nil) != (b.Max == nil) {
				return false
			}
			if b.Trend != "" && b.Trend != "up" && b.Trend != "down" && b.Trend != "flat" {
				return false
			}
		}
		if len(b.Bands) > 0 {
			if b.Max == nil || len(b.Bands) > 5 {
				return false
			}
			last := 0.0
			if b.Min != nil {
				last = *b.Min
			}
			for _, band := range b.Bands {
				if !finiteBlockNumber(band.Until) || band.Until <= last || !validBlockTone(band.Tone) {
					return false
				}
				last = band.Until
			}
			if last != *b.Max {
				return false
			}
		}
		if b.Max != nil {
			min := 0.0
			if b.Min != nil {
				min = *b.Min
			}
			if *b.Max <= min || *b.Value < min || *b.Value > *b.Max {
				return false
			}
		}
	}
	return true
}

func finiteBlockNumber(v float64) bool {
	return !math.IsNaN(v) && !math.IsInf(v, 0) && math.Abs(v) <= 1e15
}
func validBlockTone(v string) bool {
	return v == "" || v == "normal" || v == "warning" || v == "critical"
}
func validRichBlock(b StructuredBlock) bool {
	if b.Value != nil || b.Min != nil || b.Max != nil || b.Indeterminate || b.Trend != "" || len(b.Bands) > 0 {
		return false
	}
	if len(b.Text) > 2048 || !utf8.ValidString(b.Text) {
		return false
	}
	if b.Kind == "alert" {
		return b.Text != "" && len(b.Items) == 0 && len(b.Points) == 0 && b.TargetAt == nil && b.Total == nil
	}
	if b.Kind == "countdown" {
		return b.TargetAt != nil && *b.TargetAt >= 0 && *b.TargetAt <= MaxStateRevision && len(b.Items) == 0 && len(b.Points) == 0 && b.Total == nil && b.Text == ""
	}
	if b.Kind == "sparkline" {
		if len(b.Points) < 1 || len(b.Points) > 120 || len(b.Items) > 0 || b.TargetAt != nil || b.Total != nil || b.Text != "" {
			return false
		}
		last := int64(-1)
		for _, p := range b.Points {
			if p.At <= last || p.At > MaxStateRevision || !finiteBlockNumber(p.Value) {
				return false
			}
			last = p.At
		}
		return true
	}
	if b.Kind != "kv" && b.Kind != "list" && b.Kind != "timeline" && b.Kind != "pipeline" && b.Kind != "segments" {
		return false
	}
	if len(b.Items) < 1 || len(b.Items) > 12 || len(b.Points) > 0 || b.TargetAt != nil || b.Text != "" {
		return false
	}
	if b.Kind == "segments" {
		if len(b.Items) > 6 || b.Total == nil || !finiteBlockNumber(*b.Total) || *b.Total <= 0 {
			return false
		}
	} else if b.Total != nil {
		return false
	}
	ids := map[string]bool{}
	sum := 0.0
	for _, item := range b.Items {
		if !ValidTopicKey(item.ID) || len(item.ID) > 64 || ids[item.ID] || len(item.Label) > 128 || len(item.Text) > 2048 || !utf8.ValidString(item.Label) || !utf8.ValidString(item.Text) || !validBlockTone(item.Tone) {
			return false
		}
		ids[item.ID] = true
		switch b.Kind {
		case "kv":
			if item.Label == "" || item.Value != nil || item.At != nil || item.Status != "" {
				return false
			}
		case "list":
			if item.Text == "" || item.Value != nil || item.At != nil || item.Status != "" {
				return false
			}
		case "segments":
			if item.Label == "" || item.Value == nil || !finiteBlockNumber(*item.Value) || *item.Value < 0 || item.At != nil || item.Status != "" {
				return false
			}
			sum += *item.Value
		case "timeline":
			if item.At == nil || *item.At < 0 || *item.At > MaxStateRevision || item.Label == "" || item.Value != nil {
				return false
			}
			if item.Status != "pending" && item.Status != "active" && item.Status != "completed" && item.Status != "failed" {
				return false
			}
		case "pipeline":
			if item.Label == "" || item.Value != nil || item.At != nil {
				return false
			}
			if item.Status != "pending" && item.Status != "running" && item.Status != "completed" && item.Status != "failed" && item.Status != "skipped" {
				return false
			}
		}
	}
	return b.Kind != "segments" || sum <= *b.Total
}
