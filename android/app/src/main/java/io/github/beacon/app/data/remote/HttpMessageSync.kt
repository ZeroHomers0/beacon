package io.github.beacon.app.data.remote

import android.os.SystemClock
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.domain.model.ProtocolContract
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
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Bounded sync shared by manual and scheduled calls. */
class HttpMessageSync(
    private val settings: TransportSettings,
    private val store: SyncStore,
    private val shouldContinue: () -> Boolean = { true },
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    var insertedCount: Int = 0
        private set

    companion object {
        // Synchronous requests need no dispatcher thread. One short-lived idle connection also
        // serves burst receipts and polling pages, without retaining credentials in client state.
        private val http = HttpClientFactory.shared
    }

    private val deadline = nowMs() + HttpSyncLimits.RUN_BUDGET_MS

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
        val request =
            Request.Builder()
                .url(settings.endpoint.trimEnd('/') + path)
                .header("Authorization", "Bearer ${settings.token}")
        if (body != null)
            request.post(
                body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            )
        val call = http.newCall(request.build())
        call.timeout().timeout((deadline - nowMs()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            val status = response.code
            if (status == 401 || status == 403) {
                (store as? io.github.beacon.app.data.local.RuntimeMessageStore)?.let { local ->
                    val source=local.stateCache.preference("source-for:"+settings.endpoint)
                    if(source.isNotEmpty()){local.stateCache.forget(source)}
                }
                throw SyncAuthenticationException(status, stage)
            }
            if (status == 429 || status in 500..599)
                throw HttpTransientException(
                    status,
                    stage,
                    io.github.beacon.app.domain.receive.RetryAfterPolicy.delayMs(
                        response.header("Retry-After")
                    ),
                )
            if (status in 300..499) throw HttpRejectedException(status, stage)
            if (status != 200) throw SyncResponseException(stage)
            val bytes =
                requireNotNull(response.body).byteStream().use { input ->
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
        val protocol = reply.opt("protocol")
        if (
            protocol !is Int ||
                !ProtocolContract.httpSupported(protocol) ||
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

    /** Widget configuration needs the registered catalog even when polling is disabled.
     * This read never receives/acknowledges messages or changes the reception schedule. */
    fun syncTopicCatalog(): Boolean {
        settings.validate(BuildConfig.DEBUG)
        val capabilities = checkedIdentity()
        if (capabilities.optInt("topic_catalog_protocol") != 1) return false
        val server = capabilities.getString("server_id")
        val namespace = MessageIdentity.namespace(server, capabilities.getString("device_id"))
        syncTopics(namespace, server)
        return true
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
        val protocol = identity.opt("protocol")
        check(protocol is Int && ProtocolContract.httpSupported(protocol))
        val reply = request("/api/v1/realtime/config")
        return RealtimeConfigurationDecoder.decode(reply.toString(), settings.endpoint)
            .copy(
                clockOffsetMs =
                    (receivedAt - serverAt).takeIf { serverAt > 0 && it in -300_000L..300_000L },
                adaptiveHeartbeatSupported = identity.opt("reception_control") == 1,
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

    /** Pending receipts were durably saved before this bounded batch is attempted. */
    fun acknowledgePending(namespace: String) {
        val ids = store.pendingIds(namespace).take(HttpSyncLimits.ACK_BATCH_SIZE)
        if (ids.isEmpty()) return
        confirmReceipts(ids)
        store.ackBatchCompleted(namespace, ids)
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
        if (nowMs() >= deadline) throw IOException("Sync budget exhausted")
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

    private fun syncStates(namespace: String, server: String) {
        val local = store as? io.github.beacon.app.data.local.RuntimeMessageStore ?: return
        val previousSource=local.stateCache.preference("source-for:"+settings.endpoint)
        if(previousSource.isNotEmpty()&&previousSource!=namespace)local.stateCache.forget(previousSource)
        local.stateCache.preference("source-for:"+settings.endpoint,namespace)
        var initialCursor = local.stateCache.cursor(namespace)
        var cursor = initialCursor
        var snapshot = ""
        var pageCursor = ""
        var replace = cursor.isEmpty()
        val records = mutableListOf<JSONObject>()
        var snapshotBytes=0L
        fun encoded(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
        for (page in 0 until 100) {
            checkRunning()
            val query = when {
                cursor.isNotEmpty() -> "?limit=100&cursor=" + encoded(cursor)
                snapshot.isNotEmpty() -> "?limit=100&snapshot_token=" + encoded(snapshot) + "&page_cursor=" + encoded(pageCursor)
                else -> "?limit=100"
            }
            val reply = try { request("/api/v1/states$query") } catch (e: HttpRejectedException) {
                if (e.statusCode == 410) { cursor="";snapshot="";pageCursor="";records.clear();snapshotBytes=0;initialCursor=local.stateCache.cursor(namespace);replace=true;continue } else throw e
            }
            require(reply.getString("server_id") == server)
            val rows=reply.getJSONArray("states");require(rows.length()<=100)
            for(i in 0 until rows.length()){val record=rows.getJSONObject(i);records.add(record);if(replace)snapshotBytes+=record.toString().toByteArray().size}
            require(!replace||(records.size<=500&&snapshotBytes<=8*1024*1024))
            if(!reply.getBoolean("has_more")) {
                checkRunning()
                local.stateCache.apply(namespace,records,reply.getString("next_cursor"),replace,initialCursor)
                return
            }
            if(reply.has("snapshot_token")) {snapshot=reply.getString("snapshot_token");pageCursor=reply.getString("page_cursor")} else {checkRunning();val next=reply.getString("next_cursor");local.stateCache.apply(namespace,records,next,false,initialCursor);records.clear();cursor=next;initialCursor=next}
        }
        throw SyncResponseException(SyncStage.MESSAGES)
    }

    private fun syncTopics(namespace: String, server: String) {
        val local = store as? io.github.beacon.app.data.local.RuntimeMessageStore ?: return
        val previousSource=local.stateCache.preference("source-for:"+settings.endpoint)
        if(previousSource.isNotEmpty()&&previousSource!=namespace)local.stateCache.forget(previousSource)
        local.stateCache.preference("source-for:"+settings.endpoint,namespace)
        var initialCursor = local.stateCache.topicCursor(namespace)
        var cursor = initialCursor
        var snapshot = ""
        var pageCursor = ""
        var replace = cursor.isEmpty()
        val records = mutableListOf<JSONObject>()
        var snapshotBytes=0L
        fun encoded(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
        for (page in 0 until 100) {
            checkRunning()
            val query = when {
                cursor.isNotEmpty() -> "?limit=100&cursor=" + encoded(cursor)
                snapshot.isNotEmpty() -> "?limit=100&snapshot_token=" + encoded(snapshot) + "&page_cursor=" + encoded(pageCursor)
                else -> "?limit=100"
            }
            val reply = try { request("/api/v1/topics$query") } catch (e: HttpRejectedException) {
                if (e.statusCode == 410) { cursor="";snapshot="";pageCursor="";records.clear();snapshotBytes=0;initialCursor=local.stateCache.topicCursor(namespace);replace=true;continue } else throw e
            }
            require(reply.getString("server_id") == server)
            val rows=reply.getJSONArray("topics");require(rows.length()<=100)
            for(i in 0 until rows.length()){val record=rows.getJSONObject(i);records.add(record);if(replace)snapshotBytes+=record.toString().toByteArray().size}
            require(!replace||(records.size<=500&&snapshotBytes<=8*1024*1024))
            if(!reply.getBoolean("has_more")) {
                checkRunning()
                local.stateCache.applyTopics(namespace,records,reply.getString("next_cursor"),replace,initialCursor)
                return
            }
            if(reply.has("snapshot_token")) {snapshot=reply.getString("snapshot_token");pageCursor=reply.getString("page_cursor")} else {checkRunning();val next=reply.getString("next_cursor");local.stateCache.applyTopics(namespace,records,next,false,initialCursor);records.clear();cursor=next;initialCursor=next}
        }
        throw SyncResponseException(SyncStage.MESSAGES)
    }

    fun run(): String {
        insertedCount = 0
        settings.validate(BuildConfig.DEBUG)
        val capabilities = request("/api/v1/capabilities")
        val receivedTime = System.currentTimeMillis()
        val serverTime = capabilities.optLong("server_time_ms", 0)
        val protocol = capabilities.opt("protocol")
        check(protocol is Int && ProtocolContract.httpSupported(protocol))
        // The server advertises its page ceiling; honour a smaller value and keep the
        // conservative default when the field is absent or nonsensical.
        val pageSize = ProtocolContract.pageSize(capabilities.optInt("max_page_size", 0))
        val server = capabilities.getString("server_id")
        val device = capabilities.getString("device_id")
        check(server.matches(Regex("[0-9a-f]{64}")) && device.matches(Regex("[0-9a-f]{64}")))
        // Local identity separates both server and device when credentials change.
        val namespace = MessageIdentity.namespace(server, device)
        (store as? io.github.beacon.app.data.local.RuntimeMessageStore)?.stateCache?.preference("supports:"+settings.endpoint,if(capabilities.optInt("state_protocol")==1)"1"else "0")
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
                    store.ackBatchCompleted(namespace, ids)
                }
            } catch (e: IOException) {
                if (e is HttpRejectedException || e is HttpTransientException) throw e
                /* Durable queue remains. */
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
                acknowledgeCompleted = store::ackBatchCompleted,
            )
        var inserted = 0
        // Both pending replay and incremental reads share the same persistence path.
        for (pending in listOf(true, false)) {
            var cursor = if (pending) 0L else store.cursor(namespace)
            for (pageNumber in 0 until HttpSyncLimits.MAX_PAGES_PER_PATH) {
                val page =
                    request(
                        "/api/v1/messages?after=$cursor&limit=$pageSize" +
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
                insertedCount = inserted
                cursor = decoded.cursor
                if (!pending) store.advanceCursor(namespace, cursor)
                if (!decoded.hasMore) break
            }
        }
        var stateNote=""
        if(capabilities.optInt("state_protocol")==1)try{if(capabilities.optInt("topic_catalog_protocol")==1)syncTopics(namespace,server);syncStates(namespace,server)}catch(error:Exception){
            if(error is SyncAuthenticationException||error is SyncCancelledException)throw error
            stateNote="；状态同步未完成，稍后重试（普通消息已按原流程处理）"
        }
        val pendingCount = store.pendingIds(namespace).size
        return "本轮同步：新增 $inserted 条" + stateNote +
            (if (pendingCount > 0) "；待回传接收结果 $pendingCount 条（自动重试）" else "") +
            (if (retired > 0) "；已退役过期确认 $retired 条" else "") +
            (if (unavailable > 0) "；服务器已无对应记录，停止重试 $unavailable 条（本地消息保留）" else "") +
            capabilities.optLong("expired_messages", 0).let {
                if (it > 0) "；服务器累计过期 $it 条未接收消息" else ""
            }
    }
}
