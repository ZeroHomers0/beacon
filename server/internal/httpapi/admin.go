package httpapi

import (
	"crypto/subtle"
	"errors"
	"net/http"
	"time"

	"beacon.local/server/internal/auth"
	"beacon.local/server/internal/config"
	"beacon.local/server/internal/domain"
	"github.com/gin-gonic/gin"
)

func (a *API) WithWebAdmin(token string) *API { a.adminToken = token; return a }

func (a *API) registerAdmin(router *gin.Engine) {
	if a.adminToken == "" {
		return
	}
	expected := auth.HashToken("Bearer " + a.adminToken)
	admin := router.Group("/api/admin")
	admin.Use(func(c *gin.Context) {
		c.Header("Cache-Control", "no-store")
		c.Header("X-Content-Type-Options", "nosniff")
		actual := auth.HashToken(c.GetHeader("Authorization"))
		if subtle.ConstantTimeCompare([]byte(actual), []byte(expected)) != 1 {
			apiError(c.Writer, http.StatusUnauthorized, "unauthorized")
			c.Abort()
			return
		}
		// Header-only authentication and no CORS: browsers cannot submit cross-site writes.
		if c.Request.URL.RawQuery != "" {
			apiError(c.Writer, http.StatusBadRequest, "invalid_query")
			c.Abort()
			return
		}
		c.Next()
	})
	admin.GET("/overview", func(c *gin.Context) {
		devices, err := a.service.ListDevices()
		if err != nil {
			apiError(c.Writer, 500, "storage_error")
			return
		}
		stats, err := a.service.StorageStats()
		if err != nil {
			apiError(c.Writer, 500, "storage_error")
			return
		}
		applications, err := a.service.ListApplications()
		if err != nil {
			apiError(c.Writer, 500, "storage_error")
			return
		}
		writeJSON(c.Writer, 200, map[string]any{"devices": devices, "applications": applications, "storage": stats, "transport": "websocket", "server_id": a.service.ServerID(), "version": config.Version, "protocol": domain.ProtocolVersion, "admin_protocol": 1})
	})
	admin.POST("/binding", func(c *gin.Context) {
		var body struct {
			Name string `json:"name"`
		}
		if decodeJSON(c.Writer, c.Request, &body) != nil {
			apiError(c.Writer, 400, "invalid_request")
			return
		}
		code, err := a.service.CreateBindingCode(body.Name, 10*time.Minute)
		if err != nil {
			apiError(c.Writer, 400, "invalid_binding")
			return
		}
		writeJSON(c.Writer, 201, code)
	})
	admin.POST("/revoke", func(c *gin.Context) {
		var body struct {
			Device string `json:"device_id"`
		}
		if decodeJSON(c.Writer, c.Request, &body) != nil {
			apiError(c.Writer, 400, "invalid_request")
			return
		}
		if a.service.RevokeDevice(body.Device) != nil {
			apiError(c.Writer, 400, "invalid_device")
			return
		}
		writeJSON(c.Writer, 200, map[string]bool{"revoked": true})
	})
	admin.POST("/messages/clear", func(c *gin.Context) {
		var body struct{}
		if decodeJSON(c.Writer, c.Request, &body) != nil {
			apiError(c.Writer, 400, "invalid_request")
			return
		}
		count, err := a.service.ClearMessages("", false)
		if err != nil {
			apiError(c.Writer, 500, "storage_error")
			return
		}
		writeJSON(c.Writer, 200, map[string]any{"deleted": count})
	})
	admin.POST("/devices/delete", func(c *gin.Context) {
		var body struct {
			Device string `json:"device_id"`
		}
		if decodeJSON(c.Writer, c.Request, &body) != nil {
			apiError(c.Writer, 400, "invalid_request")
			return
		}
		count, err := a.service.ClearMessages(body.Device, true)
		if err != nil {
			if errors.Is(err, domain.ErrInvalidDevice) {
				apiError(c.Writer, 400, "invalid_device")
			} else {
				apiError(c.Writer, 500, "storage_error")
			}
			return
		}
		writeJSON(c.Writer, 200, map[string]any{"deleted": count, "device_deleted": true})
	})
	admin.POST("/test", func(c *gin.Context) {
		var body struct {
			Device   string `json:"device_id"`
			Title    string `json:"title"`
			Subtitle string `json:"subtitle"`
			Body     string `json:"body"`
			// Omitted means the same default a device-level push uses.
			Priority *int `json:"priority"`
		}
		if decodeJSON(c.Writer, c.Request, &body) != nil {
			apiError(c.Writer, 400, "invalid_request")
			return
		}
		message, err := a.service.PushPriority(body.Device, body.Title, body.Subtitle, body.Body, body.Priority)
		if err != nil {
			if errors.Is(err, domain.ErrStorageCapacity) {
				apiError(c.Writer, http.StatusInsufficientStorage, "storage_capacity_reached")
				return
			}
			if errors.Is(err, domain.ErrRateLimited) {
				c.Writer.Header().Set("Retry-After", "1")
				apiError(c.Writer, http.StatusTooManyRequests, "push_rate_limited")
				return
			}
			apiError(c.Writer, 400, "invalid_message")
			return
		}
		writeJSON(c.Writer, 201, message)
	})
	admin.POST("/applications", func(c *gin.Context) {
		var body struct {
			Device   string `json:"device_id"`
			Name     string `json:"name"`
			Priority int    `json:"default_priority"`
		}
		if decodeJSON(c.Writer, c.Request, &body) != nil {
			apiError(c.Writer, 400, "invalid_request")
			return
		}
		credential, err := a.service.CreateApplication(body.Device, body.Name, body.Priority)
		if err != nil {
			apiError(c.Writer, 400, "invalid_application")
			return
		}
		writeJSON(c.Writer, 201, credential)
	})
	admin.POST("/applications/revoke", func(c *gin.Context) {
		var body struct {
			ID string `json:"id"`
		}
		if decodeJSON(c.Writer, c.Request, &body) != nil {
			apiError(c.Writer, 400, "invalid_request")
			return
		}
		if a.service.RevokeApplication(body.ID) != nil {
			apiError(c.Writer, 400, "invalid_application")
			return
		}
		writeJSON(c.Writer, 200, map[string]bool{"revoked": true})
	})
}
