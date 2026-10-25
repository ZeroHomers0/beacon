package httpapi

import (
	"beacon.local/server/internal/domain"
	"context"
	"github.com/gin-gonic/gin"
	"net/http"
	"sync"
	"time"
)

type observationCache struct {
	mu      sync.Mutex
	sample  domain.OperationalStats
	until   time.Time
	failure error
}

func (a *API) operational(ctx context.Context) (domain.OperationalStats, error) {
	a.observation.mu.Lock()
	defer a.observation.mu.Unlock()
	if time.Now().Before(a.observation.until) {
		return a.observation.sample, a.observation.failure
	}
	bounded, cancel := context.WithTimeout(ctx, 250*time.Millisecond)
	defer cancel()
	sample, err := a.service.OperationalStats(bounded)
	a.observation.sample = sample
	a.observation.failure = err
	ttl := 5 * time.Second
	if err != nil {
		ttl = time.Second
	}
	a.observation.until = time.Now().Add(ttl)
	return sample, err
}

func (a *API) metricsHandler(c *gin.Context) {
	snapshot := a.metrics.snapshot()
	sample, err := a.operational(c.Request.Context())
	if err != nil {
		apiError(c.Writer, http.StatusServiceUnavailable, "observability_unavailable")
		return
	}
	snapshot["delivery"] = sample
	active := 0
	if a.stream != nil {
		active = len(a.stream.snapshot())
	}
	snapshot["active_connections"] = active
	writeJSON(c.Writer, http.StatusOK, snapshot)
}
func (a *API) readyHandler(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), 250*time.Millisecond)
	defer cancel()
	if err := a.service.Ready(ctx); err != nil {
		apiError(c.Writer, 503, "not_ready")
		return
	}
	if a.stream != nil {
		a.stream.mu.Lock()
		closed := a.stream.closed
		a.stream.mu.Unlock()
		if closed {
			apiError(c.Writer, 503, "not_ready")
			return
		}
	}
	writeJSON(c.Writer, 200, map[string]any{"ready": true})
}
