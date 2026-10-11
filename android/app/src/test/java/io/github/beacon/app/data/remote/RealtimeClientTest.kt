package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.RealtimeConfiguration
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RealtimeClientTest {
    private fun config() =
        RealtimeConfiguration(
            "a".repeat(64),
            "b".repeat(64),
            "wss://localhost/api/v1/realtime/stream",
        )

    private class Fake : RealtimeTransport {
        val connected = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var listener: RealtimeTransport.Listener? = null
        var failure: Exception? = null
        var gate: CountDownLatch? = null
        val activations = AtomicInteger()

        override fun connect(
            config: RealtimeConfiguration,
            password: String,
            listener: RealtimeTransport.Listener,
        ) {
            this.listener = listener
            connected.countDown()
            gate?.await(3, TimeUnit.SECONDS)
            failure?.let { throw it }
        }

        override fun ready() {
            activations.incrementAndGet()
        }

        override fun close() {
            closed.countDown()
        }
    }

    @Test
    fun startIsNonblockingAndStopDuringConnectPreventsSubscription() {
        val fake = Fake().apply { gate = CountDownLatch(1) }
        val client = RealtimeClient(config(), "test", {}, {}, {}, { fake })
        client.start()
        assertTrue(fake.connected.await(1, TimeUnit.SECONDS))
        client.start() // idempotent
        client.close()
        fake.gate!!.countDown()
        assertTrue(fake.closed.await(1, TimeUnit.SECONDS))
        assertEquals(0, fake.activations.get())
        assertThrows(IllegalStateException::class.java) { client.start() }
    }

    @Test
    fun duplicateDisconnectAndLateCallbacksCreateOnlyOneReplacement() {
        val first = Fake()
        val second = Fake()
        val factories = AtomicInteger()
        val connected = CountDownLatch(1)
        val client =
            RealtimeClient(
                config(),
                "test",
                {},
                { if (it == RealtimeClient.State.CONNECTED) connected.countDown() },
                {},
                { if (factories.incrementAndGet() == 1) first else second },
            )
        try {
            client.start()
            assertTrue(connected.await(1, TimeUnit.SECONDS))
            repeat(3) { first.listener!!.lost(IOException("offline")) }
            assertTrue(second.connected.await(3, TimeUnit.SECONDS))
            assertTrue(first.closed.await(1, TimeUnit.SECONDS))
            first.listener!!.lost(IOException("late"))
            assertThrows(IOException::class.java) { first.listener!!.message(byteArrayOf(1)) }
            assertEquals(2, factories.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun interruptedTlsHandshakeRetriesAndCanConnect() {
        val first =
            Fake().apply {
                failure = javax.net.ssl.SSLHandshakeException("Connection closed by peer")
            }
        val second = Fake()
        val connected = CountDownLatch(1)
        val factories = AtomicInteger()
        val client =
            RealtimeClient(
                config(),
                "test",
                {},
                { if (it == RealtimeClient.State.CONNECTED) connected.countDown() },
                {},
                { if (factories.incrementAndGet() == 1) first else second },
            )
        try {
            client.start()
            assertTrue(connected.await(3, TimeUnit.SECONDS))
            assertEquals(2, factories.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun certificateFailureIsReportedAndNeverRetried() {
        val fake =
            Fake().apply {
                failure =
                    javax.net.ssl.SSLHandshakeException("Certificate rejected").apply {
                        initCause(java.security.cert.CertificateException("Untrusted certificate"))
                    }
            }
        val rejected = CountDownLatch(1)
        val calls = AtomicInteger()
        val client =
            RealtimeClient(
                config(),
                "test",
                {},
                { if (it == RealtimeClient.State.TLS_FAILED) rejected.countDown() },
                {},
                {
                    calls.incrementAndGet()
                    fake
                },
            )
        try {
            client.start()
            assertTrue(rejected.await(1, TimeUnit.SECONDS))
            Thread.sleep(1200)
            assertEquals(1, calls.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun numericAuthorizationFailureIsReportedAndNeverRetried() {
        val fake = Fake().apply { failure = RealtimeAuthorizationFailure() }
        val auth = CountDownLatch(1)
        val calls = AtomicInteger()
        val client =
            RealtimeClient(
                config(),
                "test",
                {},
                { if (it == RealtimeClient.State.AUTH_FAILED) auth.countDown() },
                {},
                {
                    calls.incrementAndGet()
                    fake
                },
            )
        try {
            client.start()
            assertTrue(auth.await(1, TimeUnit.SECONDS))
            assertTrue(fake.closed.await(1, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            client.close()
        }
    }
}
