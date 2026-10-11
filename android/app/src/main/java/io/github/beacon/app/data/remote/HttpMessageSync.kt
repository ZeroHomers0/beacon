package io.github.beacon.app.data.remote

import io.github.beacon.app.BuildConfig
import io.github.beacon.app.domain.model.TransportSettings
import io.github.beacon.app.domain.receive.HttpRejectedException
import io.github.beacon.app.domain.receive.HttpTransientException
import io.github.beacon.app.domain.receive.ReceiveBatchPipeline
import io.github.beacon.app.domain.receive.SyncAuthenticationException
import io.github.beacon.app.domain.receive.SyncCancelledException
import io.github.beacon.app.domain.receive.SyncResponseException
import io.github.beacon.app.domain.receive.SyncStage
import io.github.beacon.app.domain.receive.SyncStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** Bounded sync shared by manual and scheduled calls. */
class HttpMessageSync(
    private val settings: TransportSettings,
    private val store: SyncStore,
    private val shouldContinue: () -> Boolean = { true },
) {
    private val deadline = System.nanoTime() + HttpSyncLimits.RUN_BUDGET_MS * 1_000_000L

    private fun request(path: String, body: JSONObject? = null): JSONObject {
        checkRunning()
        val stage =
            when (path.substringBefore('?')) {
                "/api/v1/capabilities",
                "/api/v1/realtime/config" -> SyncStage.CAPABILITIES
                "/api/v1/messages/ack",
                "/api/v1/receipts/status" -> SyncStage.ACK
                else -> SyncStage.MESSAGES
            }
        val connection =
            URL(settings.endpoint.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = HttpSyncLimits.SOCKET_TIMEOUT_MS
            connection.readTimeout = HttpSyncLimits.SOCKET_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer ${settings.token}")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            if (status == 401 || status == 403) throw SyncAuthenticationException(status, stage)
            if (status == 429 || status in 500..599) throw HttpTransientException(status, stage)
            if (status in 300..499) throw HttpRejectedException(status, stage)
            if (status != 200) throw SyncResponseException(stage)
            val bytes =
                connection.inputStream.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val block = ByteArray(8192)
                    while (true) {
                        checkRunning()
                        val count = input.read(block)
                        if (count < 0) break
                        val maximum =
                            if (stage == SyncStage.MESSAGES) HttpSyncLimits.MESSAGE_RESPONSE_BYTES
                            else HttpSyncLimits.CONTROL_RESPONSE_BYTES
                        if (buffer.size() + count > maximum) throw SyncResponseException(stage)
                        buffer.write(block, 0, count)
                    }
                    buffer.toByteArray()
                }
            return try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (_: org.json.JSONException) {
                throw SyncResponseException(stage)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Read-only authenticated protocol probe; never reads, saves or acknowledges messages. */
    fun checkServer(): String {
        val capabilities = checkedIdentity()
        val transports = capabilities.getJSONArray("transports")
        val websocket = (0 until transports.length()).any { transports.opt(it) == "websocket" }
        if (websocket) {
            val configuration = realtimeConfiguration()
            check(
                configuration.serverId == capabilities.getString("server_id") &&
                    configuration.deviceId == capabilities.getString("device_id")
            )
        }
        return if (websocket) "连接正常 · 支持 HTTP / WebSocket · 即时配置有效"
        else "连接正常 · 支持 HTTP · 不支持即时接收，请升级服务器"
    }

    fun verifyDevice(serverId: String, deviceId: String) {
        val identity = checkedIdentity()
        if (
            identity.getString("server_id") != serverId ||
                identity.getString("device_id") != deviceId
        )
            throw SyncResponseException(SyncStage.CAPABILITIES)
    }

    private fun checkedIdentity(): JSONObject {
        val reply = request("/api/v1/capabilities")
        if (
            reply.opt("protocol") != 1 ||
                reply.opt("server_id") !is String ||
                !reply.optString("server_id").matches(Regex("[0-9a-f]{64}")) ||
                reply.opt("device_id") !is String ||
                !reply.optString("device_id").matches(Regex("[0-9a-f]{64}")) ||
                reply.optJSONArray("transports")?.let { transports ->
                    (0 until transports.length()).any { transports.opt(it) == "http" }
                } != true
        )
            throw SyncResponseException(SyncStage.CAPABILITIES)
        return reply
    }

    /** Fetches same-origin stream metadata with the same bearer credential as HTTP polling. */
    fun realtimeConfiguration(): io.github.beacon.app.domain.model.RealtimeConfiguration {
        settings.validate(BuildConfig.DEBUG)
        val identity = request("/api/v1/capabilities")
        requireRetentionContract(
            identity,
            MessageIdentity.namespace(
                identity.getString("server_id"),
                identity.getString("device_id"),
            ),
        )
        val receivedAt = System.currentTimeMillis()
        val serverAt = identity.optLong("server_time_ms", 0)
        check(identity.opt("protocol") == 1)
        val reply = request("/api/v1/realtime/config")
        return RealtimeConfigurationDecoder.decode(reply.toString(), settings.endpoint)
            .copy(
                clockOffsetMs =
                    (receivedAt - serverAt).takeIf { serverAt > 0 && it in -300_000L..300_000L }
            )
            .also {
                check(
                    it.serverId == identity.getString("server_id") &&
                        it.deviceId == identity.getString("device_id")
                )
            }
    }

    fun acknowledge(id: String) {
        check(MessageIdentity.valid(id))
        confirmReceipts(listOf(id))
    }

    private fun confirmReceipts(ids: List<String>, supportsStatus: Boolean? = null): Int {
        require(ids.size in 1..HttpSyncLimits.ACK_BATCH_SIZE && ids.all(MessageIdentity::valid))
        fun submit(batch: List<String>) {
            if (
                batch.isNotEmpty() &&
                    request("/api/v1/messages/ack", JSONObject().put("ids", JSONArray(batch)))
                        .opt("accepted") != true
            )
                throw SyncResponseException(SyncStage.ACK)
        }
        try {
            submit(ids)
            return 0
        } catch (error: HttpRejectedException) {
            if (error.statusCode != 400) throw error
            val supported = supportsStatus ?: (checkedIdentity().opt("receipt_status") == true)
            if (!supported) throw error
            val statuses =
                request("/api/v1/receipts/status", JSONObject().put("ids", JSONArray(ids)))
                    .optJSONObject("statuses") ?: throw SyncResponseException(SyncStage.ACK)
            if (
                statuses.keys().asSequence().toSet() != ids.toSet() ||
                    ids.any { statuses.opt(it) !in listOf("available", "missing") }
            )
                throw SyncResponseException(SyncStage.ACK)
            val available = ids.filter { statuses.getString(it) == "available" }
            submit(available)
            // Retiring unavailable retries is not a server acknowledgement.
            return ids.size - available.size
        }
    }

    private fun checkRunning() {
        if (!shouldContinue()) throw SyncCancelledException()
        if (System.nanoTime() >= deadline) throw IOException("Sync budget exhausted")
    }

    private fun requireRetentionContract(reply: JSONObject, namespace: String) {
        if (store.requiresRetentionContract(namespace) && !reply.has("delivery_retention_protocol"))
            throw SyncResponseException(SyncStage.CAPABILITIES)
        if (reply.has("delivery_retention_protocol")) {
            try {
                DeliveryRetentionDecoder.cutoff(reply)
            } catch (_: Exception) {
                throw SyncResponseException(SyncStage.CAPABILITIES)
            }
        }
    }

    fun run(): String {
        settings.validate(BuildConfig.DEBUG)
        val capabilities = request("/api/v1/capabilities")
        val receivedTime = System.currentTimeMillis()
        val serverTime = capabilities.optLong("server_time_ms", 0)
        check(capabilities.getInt("protocol") == 1)
        val server = capabilities.getString("server_id")
        val device = capabilities.getString("device_id")
        check(server.matches(Regex("[0-9a-f]{64}")) && device.matches(Regex("[0-9a-f]{64}")))
        // Local identity separates both server and device when credentials change.
        val namespace = MessageIdentity.namespace(server, device)
        val retentionCutoff =
            try {
                DeliveryRetentionDecoder.cutoff(capabilities)
            } catch (_: Exception) {
                throw SyncResponseException(SyncStage.CAPABILITIES)
            }
        requireRetentionContract(capabilities, namespace)
        val retired = retentionCutoff?.let { store.retireExpiredRecords(namespace, it) } ?: 0
        var unavailable = 0
        fun ack(ids: List<String>) {
            if (ids.isNotEmpty())
                unavailable += confirmReceipts(ids, capabilities.opt("receipt_status") == true)
        }
        val oldPending = store.pendingIds(namespace)
        if (oldPending.isNotEmpty()) {
            try {
                oldPending.chunked(HttpSyncLimits.ACK_BATCH_SIZE).forEach { ids ->
                    ack(ids)
                    ids.forEach { store.ackCompleted(namespace, it) }
                }
            } catch (e: IOException) {
                if (e is HttpRejectedException) throw e /* Durable queue remains. */
            }
        }
        val pipeline =
            ReceiveBatchPipeline(
                store,
                { _, ids -> ack(ids) },
                shouldContinue = {
                    checkRunning()
                    true
                },
            )
        var inserted = 0
        // Both pending replay and incremental reads share the same persistence path.
        for (pending in listOf(true, false)) {
            var cursor = if (pending) 0L else store.cursor(namespace)
            for (pageNumber in 0 until HttpSyncLimits.MAX_PAGES_PER_PATH) {
                val page =
                    request(
                        "/api/v1/messages?after=$cursor&limit=${HttpSyncLimits.PAGE_SIZE}" +
                            if (pending) "&pending=1" else ""
                    )
                val decoded =
                    HttpMessagePageDecoder.decode(
                        page,
                        server,
                        namespace,
                        cursor,
                        serverTime,
                        receivedTime,
                    )
                inserted += pipeline.accept(decoded.messages)
                cursor = decoded.cursor
                if (!pending) store.advanceCursor(namespace, cursor)
                if (!decoded.hasMore) break
            }
        }
        val pendingCount = store.pendingIds(namespace).size
        return "本轮同步：新增 $inserted 条" +
            (if (pendingCount > 0) "；待回传接收结果 $pendingCount 条（自动重试）" else "") +
            (if (retired > 0) "；已退役过期确认 $retired 条" else "") +
            (if (unavailable > 0) "；服务器已无对应记录，停止重试 $unavailable 条（本地消息保留）" else "") +
            capabilities.optLong("expired_messages", 0).let {
                if (it > 0) "；服务器累计过期 $it 条未接收消息" else ""
            }
    }
}
