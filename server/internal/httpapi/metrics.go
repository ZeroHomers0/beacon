package httpapi

import (
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

// requestMetrics is a bounded, dependency-free view of what the server is doing.
//
// The whole point is to answer "is it slow, is it failing, and where" without adding a metrics
// library to a single-binary server, and without ever recording a URL: Bark paths carry
// credentials, so the only labels are the route template and the status class.
type requestMetrics struct {
	mu       sync.Mutex
	started  time.Time
	byRoute  map[string]*routeMetrics
	requests uint64
	failures uint64
}

type routeMetrics struct {
	requests uint64
	failures uint64
	total    time.Duration
	slowest  time.Duration
	// Coarse latency buckets keep the response small and avoid a per-request allocation.
	// They are mutually exclusive rather than cumulative: under 100ms, under 1s, or slower.
	under100ms uint64
	under1s    uint64
}

// Routes are a closed set (the API surface), so the map cannot grow without bound. The last slot
// is reserved for the overflow bucket, which is why the check below leaves room for it.
const maxTrackedRoutes = 64

const overflowRoute = "other"

func newRequestMetrics() *requestMetrics {
	return &requestMetrics{started: time.Now(), byRoute: make(map[string]*routeMetrics)}
}

// routeLabel prefers the route template. `NoRoute` covers every Bark path, so those collapse
// into one bucket instead of becoming an unbounded label set.
func routeLabel(c *gin.Context) string {
	if full := c.FullPath(); full != "" {
		return full
	}
	return "unmatched"
}

func (m *requestMetrics) observe(route string, status int, elapsed time.Duration) {
	m.mu.Lock()
	defer m.mu.Unlock()
	entry, ok := m.byRoute[route]
	if !ok {
		if len(m.byRoute) >= maxTrackedRoutes-1 {
			route = overflowRoute
			if entry, ok = m.byRoute[route]; !ok {
				entry = &routeMetrics{}
				m.byRoute[route] = entry
			}
		} else {
			entry = &routeMetrics{}
			m.byRoute[route] = entry
		}
	}
	m.requests++
	entry.requests++
	if status >= 500 {
		m.failures++
		entry.failures++
	}
	entry.total += elapsed
	if elapsed > entry.slowest {
		entry.slowest = elapsed
	}
	switch {
	case elapsed < 100*time.Millisecond:
		entry.under100ms++
	case elapsed < time.Second:
		entry.under1s++
	}
}

func (m *requestMetrics) snapshot() map[string]any {
	m.mu.Lock()
	defer m.mu.Unlock()
	routes := make(map[string]any, len(m.byRoute))
	for name, entry := range m.byRoute {
		var mean time.Duration
		if entry.requests > 0 {
			mean = entry.total / time.Duration(entry.requests)
		}
		routes[name] = gin.H{
			"requests":      entry.requests,
			"server_errors": entry.failures,
			"mean_ms":       float64(mean.Microseconds()) / 1000,
			"slowest_ms":    float64(entry.slowest.Microseconds()) / 1000,
			"under_100ms":   entry.under100ms,
			"under_1s":      entry.under1s,
		}
	}
	return gin.H{
		"uptime_seconds": m.timeSinceStart().Seconds(),
		"requests":       m.requests,
		"server_errors":  m.failures,
		"routes":         routes,
	}
}

func (m *requestMetrics) timeSinceStart() time.Duration { return time.Since(m.started) }

// metricsMiddleware records one sample per request. It records the route template only, never
// the request URI, and it never touches the response body.
func metricsMiddleware(metrics *requestMetrics) gin.HandlerFunc {
	return func(c *gin.Context) {
		started := time.Now()
		c.Next()
		// Gin writes 200 implicitly for handlers that never call WriteHeader.
		metrics.observe(routeLabel(c), c.Writer.Status(), time.Since(started))
	}
}
