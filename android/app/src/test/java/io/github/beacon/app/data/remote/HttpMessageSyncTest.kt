package io.github.beacon.app.data.remote

import com.sun.net.httpserver.HttpServer
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.SyncOutcome
import io.github.beacon.app.domain.model.TransportSettings
import io.github.beacon.app.domain.policy.SyncFailure
import io.github.beacon.app.domain.receive.HttpRejectedException
import io.github.beacon.app.domain.receive.SaveResult
import io.github.beacon.app.domain.receive.SyncResponseException
import io.github.beacon.app.domain.receive.SyncStage
import io.github.beacon.app.domain.receive.SyncStore
import java.net.InetSocketAddress
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HttpMessageSyncTest {
    @Test
    fun deepSleepExhaustsRunBudgetBeforeAnyRequest() = fixture { _, store, settings, _ ->
        var elapsed = 0L
        val sync = HttpMessageSync(settings, store, nowMs = { elapsed })
        elapsed = 30_001L
        val error = assertThrows(java.io.IOException::class.java) { sync.run() }
        assertEquals("Sync budget exhausted", error.message)
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun rateLimitedReceiptsKeepDurableQueueAndExposeServerCooldown() =
        fixture { server, store, settings, _ ->
            val namespace = "a".repeat(64)
            store.save(IncomingMessage(namespace, "b".repeat(64), "T", "B"))
            server.removeContext("/api/v1/messages/ack")
            server.createContext("/api/v1/messages/ack") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                exchange.responseHeaders.add("Retry-After", "120")
                exchange.sendResponseHeaders(429, -1)
                exchange.close()
            }
            val error =
                assertThrows(
                    io.github.beacon.app.domain.receive.HttpTransientException::class.java
                ) {
                    HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                        .acknowledgePending(namespace)
                }
            assertEquals(429, error.statusCode)
            assertEquals(120_000L, error.retryAfterMs)
            assertEquals(1, store.pending.size)
        }

    @Test
    fun burstReceiptsUseBoundedBatchesAndReuseTheConnection() =
        fixture { server, store, settings, _ ->
            val namespace = "a".repeat(64)
            repeat(150) {
                store.save(
                    IncomingMessage(namespace, (it + 1).toString(16).padStart(64, '0'), "T", "B")
                )
            }
            val sizes = mutableListOf<Int>()
            val ports = mutableListOf<Int>()
            server.removeContext("/api/v1/messages/ack")
            server.createContext("/api/v1/messages/ack") { exchange ->
                val ids =
                    JSONObject(exchange.requestBody.bufferedReader().readText()).getJSONArray("ids")
                sizes.add(ids.length())
                ports.add(exchange.remoteAddress.port)
                val bytes = "{\"accepted\":true}".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                .acknowledgePending(namespace)
            assertEquals(50, store.pending.size)
            HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                .acknowledgePending(namespace)
            assertTrue(store.pending.isEmpty())
            assertEquals(listOf(100, 50), sizes)
            assertEquals(1, ports.distinct().size)
        }

    @Test
    fun failedBatchKeepsEveryDurableReceipt() = fixture { server, store, settings, _ ->
        val namespace = "a".repeat(64)
        repeat(12) {
            store.save(
                IncomingMessage(namespace, (it + 1).toString(16).padStart(64, '0'), "T", "B")
            )
        }
        server.removeContext("/api/v1/messages/ack")
        server.createContext("/api/v1/messages/ack") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        assertThrows(java.io.IOException::class.java) {
            HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                .acknowledgePending(namespace)
        }
        assertEquals(12, store.pending.size)
        assertEquals(12, store.rows.size)
    }

    @Test
    fun realtimeConfigUsesBearerAndRejectsInsecureOrigin() =
        fixture { server, store, settings, acks ->
            var foreign = false
            server.createContext("/api/v1/realtime/config") { exchange ->
                assertEquals(
                    "Bearer ${settings.token}",
                    exchange.requestHeaders.getFirst("Authorization"),
                )
                val device = if (foreign) "d".repeat(64) else "b".repeat(64)
                val bytes =
                    JSONObject()
                        .put("protocol", 2)
                        .put("server_id", "a".repeat(64))
                        .put("device_id", device)
                        .put("stream_path", "/api/v1/realtime/stream")
                        .toString()
                        .toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                    .realtimeConfiguration()
            }
            assertTrue(store.rows.isEmpty())
            assertTrue(store.cursors.isEmpty())
            assertTrue(acks.isEmpty())
        }

    @Test
    fun realtimeDisabledAndUnauthorizedRemainDistinctWithoutSaving() {
        for (status in listOf(401, 503)) fixture { server, store, settings, _ ->
            server.createContext("/api/v1/realtime/config") { exchange ->
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            val error =
                assertThrows(Exception::class.java) {
                    HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                        .realtimeConfiguration()
                }
            assertEquals(
                if (status == 401) SyncOutcome.AUTH else SyncOutcome.TRANSIENT,
                SyncFailure.outcome(error),
            )
            assertTrue(store.rows.isEmpty())
        }
    }

    @Test
    fun handshakeHttpFailuresPreserveCodeWithoutSavingOrAcknowledging() {
        for (code in listOf(401, 403, 302, 404, 429, 500, 502)) fixture {
            server,
            store,
            settings,
            acks ->
            server.removeContext("/api/v1/capabilities")
            server.createContext("/api/v1/capabilities") { exchange ->
                val bytes = ("remote-secret=" + settings.token).toByteArray()
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            val error =
                assertThrows(Exception::class.java) {
                    HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                        .run()
                }
            val outcome = SyncFailure.outcome(error)
            assertEquals(
                when (code) {
                    401,
                    403 -> SyncOutcome.AUTH
                    429,
                    500,
                    502 -> SyncOutcome.TRANSIENT
                    else -> SyncOutcome.PROTOCOL
                },
                outcome,
            )
            // User-facing text is deliberately prose-only: the numeric status stays in
            // HttpRejectedException/HttpTransientException for diagnostics, never in the UI.
            val text = SyncFailure.describe(error, outcome)
            assertTrue(text, text.isNotBlank())
            assertFalse(text, text.contains("HTTP $code"))
            assertFalse(text, text.contains(settings.token))
            assertTrue(store.rows.isEmpty())
            assertTrue(store.cursors.isEmpty())
            assertTrue(acks.isEmpty())
        }
    }

    @Test
    fun htmlSuccessResponseIsRejectedWithoutEchoingRemoteContent() =
        fixture { server, store, settings, acks ->
            server.removeContext("/api/v1/capabilities")
            server.createContext("/api/v1/capabilities") { exchange ->
                val bytes = "<html>${settings.token}</html>".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            val error =
                assertThrows(SyncResponseException::class.java) {
                    HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                        .run()
                }
            assertEquals(SyncStage.CAPABILITIES, error.stage)
            assertTrue(store.rows.isEmpty())
            assertTrue(acks.isEmpty())
            assertTrue(store.cursors.isEmpty())
        }

    @Test
    fun redirectDoesNotForwardCredentialsOrFollowLocation() =
        fixture { server, store, settings, acks ->
            var followed = 0
            server.createContext("/other") { exchange ->
                followed++
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            server.removeContext("/api/v1/capabilities")
            server.createContext("/api/v1/capabilities") { exchange ->
                exchange.responseHeaders.add("Location", settings.endpoint + "/other")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            assertThrows(HttpRejectedException::class.java) {
                HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 }).run()
            }
            assertEquals(0, followed)
            assertTrue(store.rows.isEmpty())
            assertTrue(acks.isEmpty())
        }

    private class Store : SyncStore {
        val rows = mutableMapOf<Pair<String, String>, IncomingMessage>()
        val pending = mutableSetOf<Pair<String, String>>()
        val cursors = mutableMapOf<String, Long>()
        var fail = false
        var needsRetention = false

        override fun requiresRetentionContract(server: String) = needsRetention

        override fun save(message: IncomingMessage): SaveResult {
            if (fail) throw java.io.IOException("storage failure")
            val key = message.server to message.id
            pending.add(key)
            return if (rows.putIfAbsent(key, message) == null) SaveResult.INSERTED
            else SaveResult.DUPLICATE
        }

        override fun ackCompleted(server: String, id: String) {
            pending.remove(server to id)
        }

        override fun pendingIds(server: String) =
            pending.filter { it.first == server }.map { it.second }

        override fun cursor(server: String) = cursors[server] ?: 0L

        override fun advanceCursor(server: String, sequence: Long) {
            cursors[server] = sequence
        }
    }

    private fun fixture(
        subtitle: Any? = null,
        test: (HttpServer, Store, TransportSettings, MutableList<String>) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val store = Store()
        val acks = java.util.Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/api/v1/capabilities") { exchange ->
            val bytes =
                JSONObject()
                    .put("protocol", 1)
                    .put("server_id", "a".repeat(64))
                    .put("device_id", "b".repeat(64))
                    .toString()
                    .toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/v1/messages/ack") { exchange ->
            val id =
                JSONObject(exchange.requestBody.bufferedReader().readText())
                    .getJSONArray("ids")
                    .getString(0)
            if (store.rows.keys.none { it.second == id }) {
                exchange.sendResponseHeaders(500, -1)
            } else {
                acks.add(id)
                val bytes = "{\"accepted\":true}".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.createContext("/api/v1/messages") { exchange ->
            val after =
                Regex("after=(\\d+)").find(exchange.requestURI.query)?.groupValues?.get(1)?.toLong()
                    ?: 0
            val items = JSONArray()
            if (after < 1) {
                val row =
                    JSONObject()
                        .put("id", "c".repeat(64))
                        .put("sequence", 1)
                        .put("title", "T")
                        .put("body", "B")
                if (subtitle != null) row.put("subtitle", subtitle)
                items.put(row)
            }
            val bytes =
                JSONObject()
                    .put("server_id", "a".repeat(64))
                    .put("messages", items)
                    .put("next_cursor", maxOf(after, 1))
                    .put("has_more", false)
                    .toString()
                    .toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            test(
                server,
                store,
                TransportSettings("http://127.0.0.1:${server.address.port}", "d".repeat(64)),
                acks,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun realHttpPersistsBeforeAckAndDeduplicatesBothReadPaths() =
        fixture { _, store, settings, acks ->
            val sync = HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
            sync.run()
            assertEquals(1, sync.insertedCount)
            sync.run()
            assertEquals(0, sync.insertedCount)
            assertEquals(1, store.rows.size)
            assertTrue(acks.isNotEmpty())
            assertTrue(store.pending.isEmpty())
            assertEquals(1L, store.cursors.values.single())
        }

    @Test
    fun storageFailureDoesNotAckOrAdvanceCursor() = fixture { _, store, settings, acks ->
        store.fail = true
        assertThrows(java.io.IOException::class.java) {
            HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 }).run()
        }
        assertTrue(acks.isEmpty())
        assertTrue(store.cursors.isEmpty())
    }

    @Test
    fun ackFailureKeepsDurableIntentForRetry() = fixture { server, store, settings, _ ->
        var requests = 0
        server.removeContext("/api/v1/messages/ack")
        server.createContext("/api/v1/messages/ack") { exchange ->
            requests++
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        assertThrows(io.github.beacon.app.domain.receive.HttpTransientException::class.java) {
            HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 }).run()
        }
        assertEquals(1, requests)
        assertEquals(1, store.rows.size)
        assertEquals(1, store.pending.size)
        assertTrue(store.cursors.isEmpty())
    }

    @Test
    fun invalidPageDoesNotAdvanceCursor() = fixture { server, store, settings, acks ->
        server.removeContext("/api/v1/messages")
        server.createContext("/api/v1/messages") { exchange ->
            val bytes =
                "{\"server_id\":\"${"a".repeat(64)}\",\"messages\":[],\"next_cursor\":99,\"has_more\":false}"
                    .toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        assertThrows(SyncResponseException::class.java) {
            HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 }).run()
        }
        assertTrue(store.cursors.isEmpty())
        assertTrue(acks.isEmpty())
    }

    @Test
    fun rejectsRedirectInsteadOfForwardingCredentials() = fixture { server, store, settings, _ ->
        server.removeContext("/api/v1/capabilities")
        server.createContext("/api/v1/capabilities") { exchange ->
            exchange.responseHeaders.add(
                "Location",
                "http://127.0.0.1:${server.address.port}/unexpected",
            )
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        var forwarded = false
        server.createContext("/unexpected") { exchange ->
            forwarded = true
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        assertThrows(java.io.IOException::class.java) {
            HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 }).run()
        }
        assertFalse(forwarded)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun subtitleSurvivesRealHttpAndDuplicateReads() =
        fixture(subtitle = "副标题_%") { _, store, settings, acks ->
            HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 }).run()
            HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 }).run()
            assertEquals("副标题_%", store.rows.values.single().subtitle)
            assertTrue(acks.isNotEmpty())
            assertTrue(store.pending.isEmpty())
        }

    @Test
    fun invalidSubtitleTypeIsNotSavedOrAcked() =
        fixture(subtitle = 123) { _, store, settings, acks ->
            assertThrows(SyncResponseException::class.java) {
                HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 }).run()
            }
            assertTrue(store.rows.isEmpty())
            assertTrue(acks.isEmpty())
            assertTrue(store.cursors.isEmpty())
        }

    @Test
    fun oversizedUtf8SubtitleIsNotSavedOrAcked() =
        fixture(subtitle = "中".repeat(342)) { _, store, settings, acks ->
            assertThrows(SyncResponseException::class.java) {
                HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 }).run()
            }
            assertTrue(store.rows.isEmpty())
            assertTrue(acks.isEmpty())
            assertTrue(store.cursors.isEmpty())
        }

    private fun pages(server: HttpServer, count: Int, corrupt: Boolean = false) {
        server.removeContext("/api/v1/messages")
        server.createContext("/api/v1/messages") { exchange ->
            val after =
                Regex("after=(\\d+)").find(exchange.requestURI.query)!!.groupValues[1].toInt()
            val end = minOf(after + 10, count)
            val rows = JSONArray()
            for (sequence in after + 1..end) rows.put(
                JSONObject()
                    .put("id", "%064x".format(sequence))
                    .put("sequence", sequence)
                    .put("title", "T")
                    .put("body", if (corrupt && sequence == 2) 123 else "B")
            )
            val bytes =
                JSONObject()
                    .put("server_id", "a".repeat(64))
                    .put("messages", rows)
                    .put("next_cursor", maxOf(after, end))
                    .put("has_more", end < count)
                    .toString()
                    .toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun batchConfirmationSeesEveryDurableRowAndBoundsPagination() =
        fixture { server, store, settings, _ ->
            pages(server, 125)
            val sizes = mutableListOf<Int>()
            server.removeContext("/api/v1/messages/ack")
            server.createContext("/api/v1/messages/ack") { exchange ->
                val ids =
                    JSONObject(exchange.requestBody.bufferedReader().readText()).getJSONArray("ids")
                val durable =
                    (0 until ids.length()).all { index ->
                        store.rows.keys.any { it.second == ids.getString(index) }
                    }
                sizes.add(ids.length())
                val bytes = JSONObject().put("accepted", durable).toString().toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 }).run()
            assertEquals(100, store.rows.size)
            assertEquals(100L, store.cursors.values.single())
            assertEquals(List(20) { 10 }, sizes)
            assertTrue(store.pending.isEmpty())
            HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 }).run()
            assertEquals(125, store.rows.size)
            assertEquals(125L, store.cursors.values.single())
            assertTrue(store.pending.isEmpty())
        }

    @Test
    fun malformedLaterRowRejectsEntireHttpPage() = fixture { server, store, settings, acks ->
        pages(server, 3, corrupt = true)
        val error =
            assertThrows(SyncResponseException::class.java) {
                HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 }).run()
            }
        assertEquals(SyncStage.MESSAGES, error.stage)
        assertTrue(store.rows.isEmpty())
        assertTrue(store.pending.isEmpty())
        assertTrue(store.cursors.isEmpty())
        assertTrue(acks.isEmpty())
    }

    @Test
    fun stringAcceptedDoesNotClearBatchReceiptsOrAdvanceCursor() =
        fixture { server, store, settings, _ ->
            pages(server, 3)
            server.removeContext("/api/v1/messages/ack")
            server.createContext("/api/v1/messages/ack") { exchange ->
                val bytes = "{\"accepted\":\"true\"}".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            val error =
                assertThrows(SyncResponseException::class.java) {
                    HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                        .run()
                }
            assertEquals(SyncStage.ACK, error.stage)
            assertEquals(3, store.rows.size)
            assertEquals(3, store.pending.size)
            assertTrue(store.cursors.isEmpty())
        }

    @Test
    fun rotationIdentityProbeChecksServerDeviceAndNeverPersists() =
        fixture { server, store, settings, acks ->
            server.removeContext("/api/v1/capabilities")
            var device = "b".repeat(64)
            server.createContext("/api/v1/capabilities") { exchange ->
                val bytes =
                    JSONObject()
                        .put("protocol", 1)
                        .put("server_id", "a".repeat(64))
                        .put("device_id", device)
                        .put("transports", JSONArray(listOf("http")))
                        .toString()
                        .toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                .verifyDevice("a".repeat(64), "b".repeat(64))
            device = "e".repeat(64)
            assertThrows(SyncResponseException::class.java) {
                HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                    .verifyDevice("a".repeat(64), "b".repeat(64))
            }
            assertTrue(store.rows.isEmpty())
            assertTrue(store.cursors.isEmpty())
            assertTrue(acks.isEmpty())
        }

    @Test
    fun rotationProbeRejectsCoercedProtocolBeforeAnyWrite() =
        fixture { server, store, settings, acks ->
            server.removeContext("/api/v1/capabilities")
            server.createContext("/api/v1/capabilities") { exchange ->
                val bytes =
                    JSONObject()
                        .put("protocol", "1")
                        .put("server_id", "a".repeat(64))
                        .put("device_id", "b".repeat(64))
                        .put("transports", JSONArray(listOf("http")))
                        .toString()
                        .toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            assertThrows(SyncResponseException::class.java) {
                HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                    .verifyDevice("a".repeat(64), "b".repeat(64))
            }
            assertTrue(store.rows.isEmpty())
            assertTrue(acks.isEmpty())
        }

    @Test
    fun retiredRecordsCannotSilentlyDowngradeToAnUnboundedServer() =
        fixture { _, store, settings, acks ->
            store.needsRetention = true
            assertThrows(SyncResponseException::class.java) {
                HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 }).run()
            }
            assertTrue(acks.isEmpty())
            assertTrue(store.rows.isEmpty())
        }

    @Test
    fun unavailableReceiptIsReadOnlyAndMalformedStatusCannotRetire() =
        fixture { server, store, settings, _ ->
            val id = "c".repeat(64)
            server.removeContext("/api/v1/messages/ack")
            server.createContext("/api/v1/messages/ack") { exchange ->
                exchange.sendResponseHeaders(400, -1)
                exchange.close()
            }
            server.removeContext("/api/v1/capabilities")
            server.createContext("/api/v1/capabilities") { exchange ->
                val bytes =
                    JSONObject()
                        .put("protocol", 1)
                        .put("server_id", "a".repeat(64))
                        .put("device_id", "b".repeat(64))
                        .put("transports", JSONArray(listOf("http")))
                        .put("receipt_status", true)
                        .toString()
                        .toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            var status = "missing"
            server.createContext("/api/v1/receipts/status") { exchange ->
                val bytes =
                    JSONObject()
                        .put("statuses", JSONObject().put(id, status))
                        .toString()
                        .toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                .acknowledge(id)
            status = "available"
            assertThrows(HttpRejectedException::class.java) {
                HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                    .acknowledge(id)
            }
            status = "confirmed"
            assertThrows(SyncResponseException::class.java) {
                HttpMessageSync(settings, store, nowMs = { System.nanoTime() / 1_000_000 })
                    .acknowledge(id)
            }
            assertTrue(store.rows.isEmpty())
        }
}
