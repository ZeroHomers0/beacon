package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.receive.ReceivePipeline
import io.github.beacon.app.domain.receive.ReceiveStore
import io.github.beacon.app.domain.receive.SaveResult
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RealtimeMessageDecoderTest {
    private val server = "a".repeat(64)
    private val device = "b".repeat(64)
    private val decoder = RealtimeMessageDecoder(server, device, 0L)

    private fun envelope(): JSONObject =
        JSONObject()
            .put("protocol", 1)
            .put("server_id", server)
            .put(
                "message",
                JSONObject()
                    .put("id", "c".repeat(64))
                    .put("sequence", 7)
                    .put("title", "标题")
                    .put("subtitle", "副标题")
                    .put("body", "正文")
                    .put("created_at_ms", 1000L),
            )

    private fun decode(json: JSONObject): IncomingMessage =
        decoder.decode(json.toString().toByteArray(), 2000L)

    @Test
    fun usesHttpNamespaceAndPreservesMessage() {
        val result = decode(envelope())
        assertEquals(MessageIdentity.namespace(server, device), result.server)
        assertNotEquals(result.server, MessageIdentity.namespace(server, "d".repeat(64)))
        assertEquals("副标题", result.subtitle)
        assertEquals(1000L, result.createdAtMs)
        assertEquals(2000L, result.receivedAtMs)
    }

    @Test
    fun clockCorrectionKeepsUntrustedAndFutureTimesSilent() {
        val payload = envelope().toString().toByteArray()
        assertEquals(0L, RealtimeMessageDecoder(server, device).decode(payload, 2000).createdAtMs)
        assertEquals(
            900L,
            RealtimeMessageDecoder(server, device, -100L).decode(payload, 2000).createdAtMs,
        )
        assertEquals(
            0L,
            RealtimeMessageDecoder(server, device, 2000L).decode(payload, 2000).createdAtMs,
        )
        assertEquals(
            0L,
            RealtimeMessageDecoder(server, device, 300001L).decode(payload, 2000).createdAtMs,
        )
    }

    @Test
    fun rejectsForeignServerAndProtocolCoercion() {
        for (value in listOf<Any>("1", 2, JSONObject.NULL)) {
            assertThrows(Exception::class.java) { decode(envelope().put("protocol", value)) }
        }
        assertThrows(Exception::class.java) { decode(envelope().put("server_id", "d".repeat(64))) }
    }

    @Test
    fun rejectsMalformedTypesAndOversizeFields() {
        for ((key, value) in
            listOf<Pair<String, Any>>(
                "sequence" to "7",
                "sequence" to 1.5,
                "sequence" to 0,
                "created_at_ms" to -1,
                "id" to "invalid",
                "title" to 42,
                "subtitle" to true,
                "title" to "中".repeat(342),
                "body" to "x".repeat(65537),
            )) {
            val json = envelope()
            json.getJSONObject("message").put(key, value)
            assertThrows(Exception::class.java) { decode(json) }
        }
    }

    @Test
    fun supportsOptionalSubtitleAndMaximumEscapedBody() {
        val json = envelope()
        json.getJSONObject("message").remove("subtitle")
        assertEquals("", decode(json).subtitle)
        json
            .getJSONObject("message")
            .put("subtitle", JSONObject.NULL)
            .put("body", "\u0001".repeat(65536))
        assertEquals(65536, decode(json).body.length)
    }

    @Test
    fun rejectsInvalidUtf8AndExcessivePayloadBeforeParsing() {
        assertThrows(Exception::class.java) {
            decoder.decode(byteArrayOf(0xc3.toByte(), 0x28), 2000)
        }
        assertThrows(Exception::class.java) {
            decoder.decode(ByteArray(RealtimeMessageDecoder.MAX_PAYLOAD_BYTES + 1), 2000)
        }
    }

    @Test
    fun duplicateAndDeletedMessagesKeepDurableConfirmationOnNetworkFailure() {
        for (saved in listOf(SaveResult.INSERTED, SaveResult.DUPLICATE, SaveResult.DELETED)) {
            var persisted = false
            var completed = false
            val store =
                object : ReceiveStore {
                    override fun save(message: IncomingMessage): SaveResult {
                        persisted = true
                        return saved
                    }

                    override fun ackCompleted(server: String, id: String) {
                        completed = true
                    }
                }
            val result =
                ReceivePipeline(store) { _, _ ->
                        assertTrue(persisted)
                        throw IOException("offline")
                    }
                    .accept(decode(envelope()))
            assertEquals(saved, result.saved)
            assertTrue(result.ackPending)
            assertFalse(completed)
        }
    }
}
