package io.github.beacon.app.data.remote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebSocketConfigurationTest {
    private val server = "a".repeat(64)
    private val device = "b".repeat(64)
    private val fingerprint = "c".repeat(64)

    private fun json() =
        JSONObject()
            .put("protocol", 2)
            .put("server_id", server)
            .put("device_id", device)
            .put("stream_path", "/api/v1/realtime/stream")

    @Test
    fun usesAuthenticatedHttpsOriginAndBasePath() {
        val config =
            RealtimeConfigurationDecoder.decode(json().toString(), "https://push.example/beacon")
        assertEquals("wss://push.example/beacon/api/v1/realtime/stream", config.streamUrl)
    }

    @Test
    fun acceptsMinimalWebSocketMetadataWithoutLegacyFields() {
        val json =
            JSONObject()
                .put("protocol", 2)
                .put("server_id", server)
                .put("device_id", device)
                .put("stream_path", "/api/v1/realtime/stream")
        assertTrue(
            RealtimeConfigurationDecoder.decode(json.toString(), "https://push.example")
                .streamUrl
                .startsWith("wss://")
        )
    }

    @Test
    fun refusesForeignStreamAddressAndPlaintext() {
        assertThrows(Exception::class.java) {
            RealtimeConfigurationDecoder.decode(
                json().put("stream_path", "https://foreign.test").toString(),
                "https://push.example",
            )
        }
        assertThrows(Exception::class.java) {
            RealtimeConfigurationDecoder.decode(json().toString(), "http://push.example")
        }
    }
}
