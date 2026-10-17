package httpapi

import (
	"compress/gzip"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func compressHarness(t *testing.T, handler gin.HandlerFunc) *gin.Engine {
	t.Helper()
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(compressJSON())
	router.GET("/json", handler)
	return router
}

func TestCompressJSONHonoursAcceptEncoding(t *testing.T) {
	payload := strings.Repeat(`{"title":"hello"}`, 200)
	router := compressHarness(t, func(c *gin.Context) {
		c.Header("Content-Type", "application/json; charset=utf-8")
		c.String(http.StatusOK, payload)
	})

	request := httptest.NewRequest(http.MethodGet, "/json", nil)
	request.Header.Set("Accept-Encoding", "gzip, deflate")
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, request)

	response := recorder.Result()
	defer response.Body.Close()
	if got := response.Header.Get("Content-Encoding"); got != "gzip" {
		t.Fatalf("Content-Encoding = %q, want gzip", got)
	}
	if got := response.Header.Get("Vary"); got != "Accept-Encoding" {
		t.Fatalf("Vary = %q, want Accept-Encoding", got)
	}
	reader, err := gzip.NewReader(response.Body)
	if err != nil {
		t.Fatalf("gzip.NewReader: %v", err)
	}
	defer reader.Close()
	decoded, err := io.ReadAll(reader)
	if err != nil {
		t.Fatalf("read gzip body: %v", err)
	}
	if string(decoded) != payload {
		t.Fatalf("decoded body differs from payload (%d vs %d bytes)", len(decoded), len(payload))
	}
	if len(decoded) <= recorder.Body.Len() && len(payload) > 1024 {
		// A body of this shape must actually shrink, otherwise the middleware is a no-op
		// that only adds a header.
		t.Fatalf("compressed body (%d) not smaller than original (%d)", recorder.Body.Len(), len(payload))
	}
}

func TestCompressJSONLeavesIdentityAlone(t *testing.T) {
	router := compressHarness(t, func(c *gin.Context) {
		c.String(http.StatusOK, "plain")
	})

	request := httptest.NewRequest(http.MethodGet, "/json", nil)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, request)

	if got := recorder.Header().Get("Content-Encoding"); got != "" {
		t.Fatalf("Content-Encoding = %q, want none", got)
	}
	if got := recorder.Header().Get("Vary"); got != "Accept-Encoding" {
		t.Fatalf("Vary = %q, want Accept-Encoding", got)
	}
	if recorder.Body.String() != "plain" {
		t.Fatalf("body = %q, want plain", recorder.Body.String())
	}
}

func TestCompressJSONTreatsQZeroAsRefusal(t *testing.T) {
	router := compressHarness(t, func(c *gin.Context) {
		c.String(http.StatusOK, "plain")
	})

	request := httptest.NewRequest(http.MethodGet, "/json", nil)
	request.Header.Set("Accept-Encoding", "gzip;q=0")
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, request)

	if got := recorder.Header().Get("Content-Encoding"); got != "" {
		t.Fatalf("Content-Encoding = %q, want none", got)
	}
	if recorder.Body.String() != "plain" {
		t.Fatalf("body = %q, want plain", recorder.Body.String())
	}
}

func TestCompressJSONSkipsBodylessStatus(t *testing.T) {
	router := compressHarness(t, func(c *gin.Context) {
		c.Status(http.StatusNoContent)
	})

	request := httptest.NewRequest(http.MethodGet, "/json", nil)
	request.Header.Set("Accept-Encoding", "gzip")
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want 204", recorder.Code)
	}
	if got := recorder.Header().Get("Content-Encoding"); got != "" {
		t.Fatalf("Content-Encoding = %q, want none on a bodyless status", got)
	}
}

func TestCompressJSONSkipsUpgrade(t *testing.T) {
	router := compressHarness(t, func(c *gin.Context) {
		c.String(http.StatusSwitchingProtocols, "")
	})

	request := httptest.NewRequest(http.MethodGet, "/json", nil)
	request.Header.Set("Accept-Encoding", "gzip")
	request.Header.Set("Connection", "Upgrade")
	request.Header.Set("Upgrade", "websocket")
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, request)

	if got := recorder.Header().Get("Content-Encoding"); got != "" {
		t.Fatalf("Content-Encoding = %q, want none on an upgrade", got)
	}
}

func TestAcceptsGzip(t *testing.T) {
	cases := map[string]bool{
		"":                        false,
		"gzip":                    true,
		"GZIP":                    true,
		"deflate, gzip":           true,
		"gzip;q=1.0":              true,
		"gzip;q=0":                false,
		"gzip; q=0.0":             false,
		"br;q=1.0, gzip;q=0.0":    false,
		"br, deflate":             false,
		"identity":                false,
		"gzip;q=0.5, deflate;q=1": true,
	}
	for header, want := range cases {
		if got := acceptsGzip(header); got != want {
			t.Errorf("acceptsGzip(%q) = %v, want %v", header, got, want)
		}
	}
}
