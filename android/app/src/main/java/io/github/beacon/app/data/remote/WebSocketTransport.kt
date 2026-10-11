package io.github.beacon.app.data.remote

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
internal class WebSocketTransport(private val http: OkHttpClient = defaultClient()) :
    RealtimeTransport {
    constructor(heartbeatSeconds: Int) : this(defaultClient(heartbeatSeconds))

    private val closed = AtomicBoolean()

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

    override fun connect(
        config: RealtimeConfiguration,
        password: String,
        listener: RealtimeTransport.Listener,
    ) {
        config.validate()
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        val request =
            Request.Builder()
                .url(config.streamUrl)
                .header("Authorization", "Bearer $password")
                .build()
        socket =
            http.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        ready.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (closed.get()) return
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
                            if (response?.code in listOf(401, 403)) RealtimeAuthorizationFailure()
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
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
