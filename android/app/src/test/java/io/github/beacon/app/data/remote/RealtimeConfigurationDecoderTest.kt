package io.github.beacon.app.data.remote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RealtimeConfigurationDecoderTest {
    private fun json() =
        JSONObject()
            .put("protocol", 2)
            .put("server_id", "a".repeat(64))
            .put("device_id", "b".repeat(64))
            .put("stream_path", "/api/v1/realtime/stream")

    @Test
    fun acceptsSameOriginMetadataWithoutPassword() {
        val config =
            RealtimeConfigurationDecoder.decode(json().toString(), "https://example.test/beacon")
        assertEquals("wss://example.test/beacon/api/v1/realtime/stream", config.streamUrl)
    }

    @Test
    fun rejectsOldProtocolPlaintextForeignPathAndSecrets() {
        for ((key, value) in
            listOf(
                "protocol" to 1,
                "protocol" to "2",
                "stream_path" to "wss://foreign.test/",
                "password" to "secret",
                "server_id" to "wrong",
            )) {
            assertThrows(Exception::class.java) {
                RealtimeConfigurationDecoder.decode(
                    json().put(key, value).toString(),
                    "https://example.test",
                )
            }
        }
        for (endpoint in
            listOf(
                "http://example.test",
                "https://user:password@example.test",
                "https://example.test?token=secret",
            )) {
            assertThrows(Exception::class.java) {
                RealtimeConfigurationDecoder.decode(json().toString(), endpoint)
            }
        }
    }
}
