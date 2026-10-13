package io.github.beacon.app.data.remote

import android.os.SystemClock
import io.github.beacon.app.domain.model.RealtimeConfiguration
import io.github.beacon.app.domain.model.RealtimeHeartbeat
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Native WSS, same-origin URL supplied by the authenticated HTTP configuration. */
internal class WebSocketTransport(
    private val http: OkHttpClient = defaultClient(),
    private val heartbeatPolicy: io.github.beacon.app.domain.receive.AdaptiveHeartbeatPolicy? =
        null,
    private val controlIntervalSeconds: Int = 0,
    private val batch: Boolean = false,
    private val onVerified: (Int) -> Unit = {},
    private val onHeartbeat: () -> Unit = {},
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : RealtimeTransport {
    constructor(heartbeatSeconds: Int) : this(defaultClient(heartbeatSeconds))

    private val closed = AtomicBoolean()

    override val heartbeatSeconds: Int
        get() =
            heartbeatPolicy?.seconds
                ?: controlIntervalSeconds.takeIf { it > 0 }
                ?: TimeUnit.MILLISECONDS.toSeconds(http.pingIntervalMillis.toLong()).toInt()

    @Volatile private var longestProbeSeconds = 0
    override val longestHeartbeatSeconds: Int
        get() = maxOf(longestProbeSeconds, heartbeatSeconds)

    companion object {
        internal fun defaultClient(
            heartbeatSeconds: Int = RealtimeHeartbeat.DEFAULT_SECONDS
        ): OkHttpClient {
            RealtimeHeartbeat.validate(heartbeatSeconds)
            return OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                // One heartbeat owner also detects blackholed connections without OS callbacks.
                .pingInterval(heartbeatSeconds.toLong(), TimeUnit.SECONDS)
                .build()
        }
    }

    private var socket: WebSocket? = null
    private var listener: RealtimeTransport.Listener? = null
    private var waiting = false
    private var aligned = false
    private var nextProbeAt = 0L
    private var probe: java.util.concurrent.ScheduledFuture<*>? = null
    private val timer by lazy {
        java.util.concurrent.ScheduledThreadPoolExecutor(1).apply {
            removeOnCancelPolicy = true
            // Keep one sleeping worker until close, without repeated keep-alive timeout checks
            // while a distant heartbeat is queued.
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        }
    }

    override fun connect(
        config: RealtimeConfiguration,
        password: String,
        listener: RealtimeTransport.Listener,
    ) {
        config.validate()
        this.listener = listener
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        val request =
            Request.Builder()
                .url(config.streamUrl)
                .header("Authorization", "Bearer $password")
                .apply {
                    if (controlIntervalSeconds > 0) {
                        header("X-Beacon-Heartbeat", "1")
                        if (batch) header("X-Beacon-Reception", "batch")
                    }
                }
                .build()
        socket =
            (if (controlIntervalSeconds > 0)
                    http.newBuilder().pingInterval(0, TimeUnit.SECONDS).build()
                else http)
                .newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            ready.countDown()
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (closed.get()) return
                            if (controlIntervalSeconds > 0 && text == "@beacon/heartbeat_ack/1") {
                                synchronized(this@WebSocketTransport) {
                                    if (!waiting) return
                                    waiting = false
                                    if (!aligned) heartbeatPolicy?.acknowledged()?.let(onVerified)
                                    scheduleProbe()
                                }
                                onHeartbeat()
                                return
                            }
                            try {
                                val bytes = text.toByteArray(Charsets.UTF_8)
                                require(bytes.size <= RealtimeMessageDecoder.MAX_PAYLOAD_BYTES)
                                listener.message(bytes)
                            } catch (error: Exception) {
                                webSocket.cancel()
                                listener.lost(error)
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            webSocket.cancel()
                            listener.lost(IOException("Unexpected binary message"))
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            val error =
                                if (response?.code in listOf(401, 403))
                                    RealtimeAuthorizationFailure()
                                else if (
                                    response != null &&
                                        (response.code == 429 || response.code in 500..599)
                                )
                                    io.github.beacon.app.domain.receive.HttpTransientException(
                                        response.code,
                                        io.github.beacon.app.domain.receive.SyncStage.CAPABILITIES,
                                        io.github.beacon.app.domain.receive.RetryAfterPolicy
                                            .delayMs(response.header("Retry-After")),
                                    )
                                else t
                            failure = error
                            response?.close()
                            ready.countDown()
                            if (!closed.get()) listener.lost(error)
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, null)
                            if (!closed.get()) listener.lost(IOException("Stream closed"))
                        }
                    },
                )
        if (!ready.await(10, TimeUnit.SECONDS)) {
            close()
            throw IOException("Stream connection timed out")
        }
        failure?.let { throw it }
        check(!closed.get())
    }

    override fun ready() {
        check(!closed.get())
        if (controlIntervalSeconds > 0) synchronized(this) { scheduleProbe() }
    }

    private fun scheduleProbe() {
        if (closed.get()) return
        probe?.cancel(false)
        val seconds = heartbeatPolicy?.seconds ?: controlIntervalSeconds
        longestProbeSeconds = maxOf(longestProbeSeconds, seconds)
        nextProbeAt = nowMs() + TimeUnit.SECONDS.toMillis(seconds.toLong())
        probe =
            timer.schedule(
                { synchronized(this) { sendProbe() } },
                seconds.toLong(),
                TimeUnit.SECONDS,
            )
    }

    private fun sendProbe(coalesced: Boolean = false) {
        if (closed.get() || waiting) return
        probe?.cancel(false)
        waiting = true
        aligned = coalesced
        socket?.send("@beacon/heartbeat/1")
        probe =
            timer.schedule(
                {
                    synchronized(this) {
                        if (closed.get() || !waiting) return@schedule
                        heartbeatPolicy?.timedOut()?.let(onVerified)
                        close()
                        listener?.lost(
                            java.net.SocketTimeoutException("Heartbeat acknowledgement timed out")
                        )
                    }
                },
                io.github.beacon.app.domain.receive.AdaptiveHeartbeatPolicy.ACK_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
    }

    override fun coalesceHeartbeat() {
        if (controlIntervalSeconds <= 0) return
        synchronized(this) {
            val remaining = nextProbeAt - nowMs()
            if (
                !waiting &&
                    remaining <=
                        io.github.beacon.app.domain.receive.AdaptiveHeartbeatPolicy.ALIGN_WINDOW_MS
            )
                sendProbe(true)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (controlIntervalSeconds > 0) {
            probe?.cancel(false)
            timer.shutdownNow()
        }
        socket?.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
