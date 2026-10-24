package io.github.beacon.app

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.TransportSettings
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.data.local.RuntimeMessageStore
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A stalled explicit probe must not delay local data. Automatic probes may reuse a fresh runtime verdict. */
@RunWith(AndroidJUnit4::class)
class StartupLocalMessagesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!predicate()) {
            if (SystemClock.elapsedRealtime() >= deadline) fail("Timed out: $description")
            Thread.sleep(10)
        }
    }

    @Test fun savedFirstPageIsAvailableBeforeSlowManualProbeCompletes() {
        val socket = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val slow = AtomicBoolean(false)
        val probeStarted = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        executor.execute {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        if (slow.get()) {
                            probeStarted.countDown()
                            releaseProbe.await(20, TimeUnit.SECONDS)
                        }
                        val body = """{"protocol":1,"server_id":"${"b".repeat(64)}","device_id":"${"c".repeat(64)}","transports":["http"]}""".toByteArray()
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body)
                            flush()
                        }
                    }
                } catch (_: java.io.IOException) {
                    if (!socket.isClosed) throw IllegalStateException("Startup test server failed")
                }
            }
        }
        var repository: RuntimeRepository? = null
        try {
            val savedId = "startup-saved-${java.util.UUID.randomUUID()}"
            RuntimeMessageStore(instrumentation.targetContext).use { store ->
                store.save(IncomingMessage("startup-local", savedId, "Saved startup message", "Persisted before connecting", receivedAtMs = System.currentTimeMillis()))
            }
            main { repository = RuntimeRepository().also { it.start(instrumentation.targetContext) } }
            await("initial connection") { repository!!.state.value.messagesLoaded && !repository!!.state.value.messageBusy }
            await("saved test message") { !repository!!.state.value.messageBusy && repository!!.state.value.messages.any { it.id == savedId } }
            main { repository!!.transport(TransportSettings("http://127.0.0.1:${socket.localPort}", "ab".repeat(32))) }
            await("configuration and initial probe") { !repository!!.state.value.messageBusy && repository!!.state.value.serverCheckedAtMs > 0 && !repository!!.state.value.serverChecking }
            main { repository!!.close() }
            slow.set(true)
            val start = SystemClock.elapsedRealtime()
            main {
                repository = RuntimeRepository().also {
                    it.start(instrumentation.targetContext)
                    it.checkServer()
                }
            }
            await("saved first page") { repository!!.state.value.messagesLoaded && !repository!!.state.value.messageBusy }
            val localPageMs = SystemClock.elapsedRealtime() - start
            assertTrue(repository!!.state.value.messages.any { it.id == savedId })
            assertEquals("Probe must not have returned", 0L, repository!!.state.value.serverCheckedAtMs)
            assertTrue("Explicit check must still run", probeStarted.await(5, TimeUnit.SECONDS))
            assertEquals(1L, releaseProbe.count)
            Log.i("StartupLocalMessagesTest", "Saved first page ready in ${localPageMs}ms while automatic probe remains blocked")
            val refreshStart = SystemClock.elapsedRealtime()
            main { repository!!.readMessages() }
            await("message refresh while probe blocked") { !repository!!.state.value.messageBusy }
            assertTrue(repository!!.state.value.messages.any { it.id == savedId })
            assertTrue("Local refresh must finish before a full socket timeout", SystemClock.elapsedRealtime() - refreshStart < 5000)
            Log.i("StartupLocalMessagesTest", "Local refresh ready in ${SystemClock.elapsedRealtime() - refreshStart}ms and receive plan loaded while probe remains blocked")
            releaseProbe.countDown()
            await("completed probe") { repository!!.state.value.serverCheckedAtMs > 0 }
            await("receive plan") { repository!!.state.value.receivePlanLoaded }
        } finally {
            releaseProbe.countDown()
            main { repository?.close() }
            socket.close()
            executor.shutdownNow()
        }
    }
}
