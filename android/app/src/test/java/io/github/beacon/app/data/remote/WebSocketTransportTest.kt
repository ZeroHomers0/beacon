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
