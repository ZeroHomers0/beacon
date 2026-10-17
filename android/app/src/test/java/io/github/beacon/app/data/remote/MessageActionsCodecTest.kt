package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.MessageLinkAction
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessageActionsCodecTest {
    private val action = MessageLinkAction("查看日志", "https://example.com:8443/logs?run=1#failure")
    @Test fun roundTripAndMissingActions() {
        assertEquals(listOf(action), MessageActionsCodec.decode(MessageActionsCodec.encode(listOf(action))))
        assertEquals(emptyList<MessageLinkAction>(), MessageActionsCodec.decode(JSONObject()))
        assertEquals(emptyList<MessageLinkAction>(), MessageActionsCodec.decode("[]"))
    }
    @Test fun rejectsUntrustedShapes() {
        listOf("null", "{}", "[null]", "[{\"title\":\"Open\",\"url\":\"https://example.com\",\"extra\":1}]",
            "[{\"title\":1,\"url\":\"https://example.com\"}]", "[] trailing").forEach { json ->
            assertThrows(Exception::class.java) { MessageActionsCodec.decode(json) }
        }
        assertThrows(Exception::class.java) { MessageActionsCodec.decode(JSONObject().put("actions", JSONObject.NULL)) }
    }
    @Test fun rejectsUnsafeUrlsAndByteLimitViolations() {
        listOf("http://example.com", "javascript:alert(1)", "/relative", "https://user:password@example.com",
            "https://example.com:0", "https://example.com:65536", "https://example.com:",
            "https://example.com:abc", "https://exa mple.com", "https://example.com/a\u00a0b", "https://").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { MessageActionsCodec.validate(listOf(action.copy(url = url))) }
        }
        listOf(" ", "title\n", "中".repeat(22)).forEach { title ->
            assertThrows(IllegalArgumentException::class.java) { MessageActionsCodec.validate(listOf(action.copy(title = title))) }
        }
        assertThrows(IllegalArgumentException::class.java) { MessageActionsCodec.validate(List(4) { action }) }
        assertThrows(IllegalArgumentException::class.java) { MessageActionsCodec.validate(listOf(action.copy(url = "https://example.com/" + "a".repeat(2048)))) }
    }
    @Test fun realtimeTransportPreservesAndRejectsActions() {
        val server = "a".repeat(64)
        val row = JSONObject().put("id", "c".repeat(64)).put("sequence", 1).put("created_at_ms", 1000L)
            .put("title", "Build failed").put("body", "Logs available")
            .put("actions", org.json.JSONArray(MessageActionsCodec.encode(listOf(action))))
        val envelope = JSONObject().put("protocol", 1).put("server_id", server).put("message", row)
        val decoder = RealtimeMessageDecoder(server, "b".repeat(64), 0L)
        assertEquals(listOf(action), decoder.decode(envelope.toString().toByteArray(), 2000L).actions)
        row.put("actions", JSONObject.NULL)
        assertThrows(Exception::class.java) { decoder.decode(envelope.toString().toByteArray(), 2000L) }
    }
}
