package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.RealtimeConfiguration
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RealtimeClientTest {
    private fun clientWithClock(
        configuration: RealtimeConfiguration,
        password: String,
        receive: (ByteArray) -> Unit,
        onState: (RealtimeClient.State) -> Unit,
        onSubscribed: () -> Unit,
        transportFactory: (Int) -> RealtimeTransport,
    ) =
        RealtimeClient(
            configuration,
            password,
            receive,
            onState,
            onSubscribed,
            transportFactory,
            nowMs = { System.nanoTime() / 1_000_000 },
        )

    @Test
    fun serverCooldownPreventsEarlyReconnectAndCloseCancelsIt() {
        val fake =
            Fake().apply {
                failure =
                    io.github.beacon.app.domain.receive.HttpTransientException(
                        429,
                        io.github.beacon.app.domain.receive.SyncStage.CAPABILITIES,
                        60_000,
                    )
            }
        val retrying = CountDownLatch(1)
        val factories = AtomicInteger()
        val client =
            clientWithClock(
                config(),
                "test",
                {},
                {
                    if (it == RealtimeClient.State.RETRYING) retrying.countDown()
                },
                {},
                {
                    factories.incrementAndGet()
                    fake
                },
            )
        try {
            client.start()
            assertTrue(retrying.await(1, TimeUnit.SECONDS))
            // Normal first retry is <= 1 second. Retry-After must override that deadline.
            Thread.sleep(1_200)
            assertEquals(1, factories.get())
        } finally {
            client.close()
        }
        assertTrue(fake.closed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun longHeartbeatDoesNotResetBackoffAfterTwoMinutesButSleepCountsForStability() {
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val attempts = AtomicInteger()
        val states = java.util.concurrent.LinkedBlockingQueue<RealtimeClient.State>()
        var latest: RealtimeTransport.Listener? = null
        val client =
            RealtimeClient(
                config(),
                "test",
                {},
                { states.put(it) },
                {},
                {
                    val attempt = attempts.incrementAndGet()
                    object : RealtimeTransport {
                        override val heartbeatSeconds = 300

                        override fun connect(
                            config: RealtimeConfiguration,
                            password: String,
                            listener: RealtimeTransport.Listener,
                        ) {
                            if (attempt == 1) throw IOException("first failure")
                            latest = listener
                        }

                        override fun ready() {}

                        override fun close() {}
                    }
                },
                nowMs = clock::get,
            )
        fun awaitState(expected: RealtimeClient.State) {
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (System.nanoTime() < limit) {
                if (states.poll(1, TimeUnit.SECONDS) == expected) return
            }
            fail("Missing $expected")
        }
        try {
            client.start()
            awaitState(RealtimeClient.State.RETRYING)
            awaitState(RealtimeClient.State.CONNECTED)
            clock.set(130_000)
            latest!!.lost(IOException("before two heartbeats"))
            awaitState(RealtimeClient.State.RETRYING)
            assertEquals(2, client.diagnostics.retries)
            awaitState(RealtimeClient.State.CONNECTED)
            clock.addAndGet(600_000) // Simulate deep sleep without waiting ten minutes.
            latest!!.lost(IOException("after stable elapsed time"))
            awaitState(RealtimeClient.State.RETRYING)
            assertEquals(1, client.diagnostics.retries)
        } finally {
            client.close()
        }
    }

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
        val client = clientWithClock(config(), "test", {}, {}, {}, { fake })
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
            clientWithClock(
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
            clientWithClock(
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
            clientWithClock(
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
            clientWithClock(
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
