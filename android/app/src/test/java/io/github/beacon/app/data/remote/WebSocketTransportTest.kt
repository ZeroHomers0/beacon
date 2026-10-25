package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.RealtimeConfiguration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class WebSocketTransportTest {
    @Test
    fun overdueHeartbeatAfterDeepSleepIsAcknowledgedWithoutEnteringMessagePersistence() {
        val certificate =
            HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("127.0.0.1")
                .build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls =
            HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        val acknowledged = CountDownLatch(1)
        val changes = CountDownLatch(2)
        val beats = java.util.concurrent.atomic.AtomicInteger()
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            assertEquals("@beacon/heartbeat/1", text)
                            webSocket.send("@beacon/state_changed/1")
                            webSocket.send("@beacon/topics_changed/1")
                            webSocket.send("@beacon/heartbeat_ack/1")
                        }
                    }
                )
        )
        server.start()
        var elapsed = 0L
        val transport =
            WebSocketTransport(
                http =
                    WebSocketTransport.defaultClient()
                        .newBuilder()
                        .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                        .build(),
                controlIntervalSeconds = 60,
                nowMs = { elapsed },
                batch = true,
                onHeartbeat = { beats.incrementAndGet(); acknowledged.countDown() },
                onStateChanged = { changes.countDown() },
            )
        try {
            transport.connect(
                configuration(
                    server.url("/api/v1/realtime/stream").toString().replace("https://", "wss://")
                ),
                "secret",
                object : RealtimeTransport.Listener {
                    override fun lost(error: Throwable) {
                        fail(error.toString())
                    }

                    override fun message(payload: ByteArray) {
                        fail("Heartbeat entered message decoder")
                    }
                },
            )
            transport.ready()
            elapsed = 61_000 // The executor still has its original minute-long delay.
            transport.coalesceHeartbeat()
            assertTrue(acknowledged.await(3, TimeUnit.SECONDS))
            assertTrue(changes.await(3, TimeUnit.SECONDS))
            assertEquals("Change hints must not invoke the heartbeat callback",1,beats.get())
            val request = server.takeRequest()
            assertEquals("1", request.getHeader("X-Beacon-Heartbeat"))
            assertEquals("batch", request.getHeader("X-Beacon-Reception"))
        } finally {
            transport.close()
            server.close()
        }
    }

    @Test
    fun rejectedUpgradePreservesRetryAfter() {
        val certificate =
            HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("127.0.0.1")
                .build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls =
            HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        server.start()
        val transport =
            WebSocketTransport(
                WebSocketTransport.defaultClient()
                    .newBuilder()
                    .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                    .build()
            )
        try {
            val error =
                assertThrows(
                    io.github.beacon.app.domain.receive.HttpTransientException::class.java
                ) {
                    transport.connect(
                        configuration(
                            server
                                .url("/api/v1/realtime/stream")
                                .toString()
                                .replace("https://", "wss://")
                        ),
                        "secret",
                        object : RealtimeTransport.Listener {
                            override fun lost(error: Throwable) = Unit

                            override fun message(payload: ByteArray) = Unit
                        },
                    )
                }
            assertEquals(429, error.statusCode)
            assertEquals(120_000L, error.retryAfterMs)
        } finally {
            transport.close()
            server.close()
        }
    }

    @Test
    fun supportedHeartbeatsConfigureTheActualClientAndRejectUnboundedValues() {
        for (seconds in io.github.beacon.app.domain.model.RealtimeHeartbeat.options) {
            val client = WebSocketTransport.defaultClient(seconds)
            assertEquals(seconds * 1000, client.pingIntervalMillis)
            assertEquals(0, client.readTimeoutMillis)
        }
        for (seconds in listOf(0, 45, 181, 300)) {
            assertThrows(IllegalArgumentException::class.java) { WebSocketTransport(seconds) }
        }
    }

    private fun configuration(url: String): RealtimeConfiguration {
        val server = "a".repeat(64)
        val device = "b".repeat(64)
        return RealtimeConfiguration(server, device, url)
    }

    @Test
    fun trustedTlsReceivesTextAndUsesHeaderCredential() {
        val certificate =
            HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("127.0.0.1")
                .build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls =
            HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("message payload")
                        }
                    }
                )
        )
        server.start()
        val client =
            WebSocketTransport.defaultClient(180)
                .newBuilder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        val transport = WebSocketTransport(client)
        try {
            val received = CountDownLatch(1)
            transport.connect(
                configuration(
                    server.url("/api/v1/realtime/stream").toString().replace("https://", "wss://")
                ),
                "receive-secret",
                object : RealtimeTransport.Listener {
                    override fun lost(error: Throwable) = Unit

                    override fun message(payload: ByteArray) {
                        assertEquals("message payload", payload.toString(Charsets.UTF_8))
                        received.countDown()
                    }
                },
            )
            assertTrue(received.await(3, TimeUnit.SECONDS))
            val request = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("Bearer receive-secret", request.getHeader("Authorization"))
            assertEquals("/api/v1/realtime/stream", request.path)
        } finally {
            transport.close()
            server.close()
        }
    }

    @Test
    fun productionClientRejectsUntrustedTls() {
        val certificate =
            HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("127.0.0.1")
                .build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val server = MockWebServer()
        server.useHttps(tls.sslSocketFactory(), false)
        server.start()
        val transport = WebSocketTransport()
        try {
            val error =
                assertThrows(SSLException::class.java) {
                    transport.connect(
                        configuration(
                            server
                                .url("/api/v1/realtime/stream")
                                .toString()
                                .replace("https://", "wss://")
                        ),
                        "receive-secret",
                        object : RealtimeTransport.Listener {
                            override fun lost(error: Throwable) = Unit

                            override fun message(payload: ByteArray) = Unit
                        },
                    )
                }
            assertNotNull(error)
        } finally {
            transport.close()
            server.close()
        }
    }

    @Test
    fun trustedCertificateStillRequiresMatchingHostname() {
        val certificate =
            HeldCertificate.Builder()
                .commonName("different.example")
                .addSubjectAlternativeName("different.example")
                .build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls =
            HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        val client =
            OkHttpClient.Builder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .followRedirects(false)
                .build()
        val transport = WebSocketTransport(client)
        try {
            assertThrows(javax.net.ssl.SSLPeerUnverifiedException::class.java) {
                transport.connect(
                    configuration(
                        server
                            .url("/api/v1/realtime/stream")
                            .toString()
                            .replace("https://", "wss://")
                    ),
                    "receive-secret",
                    object : RealtimeTransport.Listener {
                        override fun lost(error: Throwable) = Unit

                        override fun message(payload: ByteArray) = Unit
                    },
                )
            }
        } finally {
            transport.close()
            server.close()
        }
    }
}
