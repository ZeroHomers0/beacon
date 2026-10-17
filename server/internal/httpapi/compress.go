package httpapi

import (
	"compress/gzip"
	"net/http"
	"strconv"
	"strings"
	"sync"

	"github.com/gin-gonic/gin"
)

// gzipLevel favours latency over the last few percent of ratio. A message page is
// mostly JSON punctuation and repeated keys, so even the fastest level removes
// most of the wire bytes.
const gzipLevel = gzip.BestSpeed

var gzipWriters = sync.Pool{
	New: func() any {
		writer, err := gzip.NewWriterLevel(nil, gzipLevel)
		if err != nil {
			// BestSpeed is a valid level; this cannot happen.
			panic(err)
		}
		return writer
	},
}

// compressJSON negotiates gzip for credential-bearing API responses.
//
// The Android client sends `Accept-Encoding: gzip` through OkHttp, which
// decompresses transparently, so this costs the client nothing and typically
// removes 70-80% of a message page. Compression is applied only when the caller
// explicitly asked for it: Go's own client and any hand-rolled probe that does
// not advertise gzip keeps receiving identity bytes.
//
// Two response kinds are deliberately left alone:
//   - a WebSocket upgrade, whose body is a framed stream the hijacked connection
//     writes directly, and
//   - anything without a body (204/304), where Content-Encoding would be a lie.
func compressJSON() gin.HandlerFunc {
	return func(c *gin.Context) {
		// Vary is set even when nothing is compressed so a shared cache cannot hand a
		// gzip body to a client that never asked for one.
		c.Header("Vary", "Accept-Encoding")
		if !acceptsGzip(c.Request.Header.Get("Accept-Encoding")) || isUpgrade(c.Request) {
			c.Next()
			return
		}
		writer := &gzipResponseWriter{ResponseWriter: c.Writer}
		c.Writer = writer
		c.Next()
		writer.finish()
	}
}

func acceptsGzip(header string) bool {
	for _, part := range strings.Split(header, ",") {
		encoding, params, _ := strings.Cut(part, ";")
		if !strings.EqualFold(strings.TrimSpace(encoding), "gzip") {
			continue
		}
		if quality, ok := qValue(params); ok && quality == 0 {
			// `gzip;q=0` is an explicit refusal, not an acceptance.
			return false
		}
		return true
	}
	return false
}

// qValue reads the optional `q=` parameter of a single Accept-Encoding entry.
func qValue(params string) (float64, bool) {
	for _, param := range strings.Split(params, ";") {
		name, value, found := strings.Cut(param, "=")
		if !found || !strings.EqualFold(strings.TrimSpace(name), "q") {
			continue
		}
		quality, err := strconv.ParseFloat(strings.TrimSpace(value), 64)
		if err != nil {
			return 0, false
		}
		return quality, true
	}
	return 0, false
}

func isUpgrade(r *http.Request) bool {
	return strings.Contains(strings.ToLower(r.Header.Get("Connection")), "upgrade") ||
		r.Header.Get("Upgrade") != ""
}

// gzipResponseWriter defers both the status line and the encoder setup until the
// first body byte, so a handler that sets its own Content-Length or writes no body
// at all still produces a valid response.
type gzipResponseWriter struct {
	gin.ResponseWriter
	writer    *gzip.Writer
	committed bool
	compress  bool
}

func (w *gzipResponseWriter) WriteHeader(status int) {
	if w.committed {
		return
	}
	// A bodyless status must not advertise an encoding it will not send.
	if status == http.StatusNoContent || status == http.StatusNotModified || status < 200 {
		w.ResponseWriter.WriteHeader(status)
		w.committed = true
		return
	}
	w.committed = true
	w.startCompression()
	w.ResponseWriter.WriteHeader(status)
}

func (w *gzipResponseWriter) Write(payload []byte) (int, error) {
	if !w.committed {
		w.WriteHeader(http.StatusOK)
	}
	if !w.compress {
		return w.ResponseWriter.Write(payload)
	}
	return w.writer.Write(payload)
}

// WriteHeaderNow must be intercepted as well: gin calls it on the way to writing any
// body, and the embedded writer would flush the headers before Content-Encoding could
// be added, producing a gzip body labelled as identity.
func (w *gzipResponseWriter) WriteHeaderNow() {
	if !w.committed {
		w.WriteHeader(http.StatusOK)
	}
}

func (w *gzipResponseWriter) startCompression() {
	header := w.ResponseWriter.Header()
	header.Set("Content-Encoding", "gzip")
	// The compressed length is unknown and the uncompressed one no longer applies.
	header.Del("Content-Length")
	w.compress = true
	w.writer = gzipWriters.Get().(*gzip.Writer)
	w.writer.Reset(w.ResponseWriter)
}

func (w *gzipResponseWriter) finish() {
	if !w.compress {
		return
	}
	_ = w.writer.Close()
	w.writer.Reset(nil)
	gzipWriters.Put(w.writer)
	w.writer = nil
}
