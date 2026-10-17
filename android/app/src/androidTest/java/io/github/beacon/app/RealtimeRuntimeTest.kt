package io.github.beacon.app

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Binder/runtime/foreground service. Synthetic configuration, no user device required. */
@RunWith(AndroidJUnit4::class)
class RealtimeRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!predicate()) {
            if (System.nanoTime() > deadline) fail("Timed out: $label")
            Thread.sleep(50)
        }
    }

    @Test
    fun authenticationFailureStopsForegroundWithoutPeriodicChecks() {
        val context = instrumentation.targetContext
        val activity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val response = Thread {
            runCatching {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) {}
                    socket
                        .getOutputStream()
                        .write(
                            "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII)
                        )
                }
            }
        }
            .apply {
                isDaemon = true
                start()
            }
        lateinit var repository: RuntimeRepository
        main {
            repository = RuntimeRepository()
            repository.start(context)
        }
        try {
            await("loaded control") {
                repository.state.value.receivePlanLoaded && !repository.state.value.messageBusy
            }
            main {
                repository.setPolling(
                    repository.state.value.pollingState.config.copy(enabled = false)
                )
            }
            await("paused") {
                !repository.state.value.messageBusy &&
                    !repository.state.value.pollingState.config.enabled
            }
            main { repository.receivePlan(ReceivePlan(ReceiveMode.REALTIME)) }
            await("realtime mode") {
                !repository.state.value.messageBusy &&
                    repository.state.value.receivePlan.mode == ReceiveMode.REALTIME
            }
            main {
                repository.transport(
                    TransportSettings("http://127.0.0.1:${server.localPort}", "a".repeat(64))
                )
            }
            await("local endpoint") {
                !repository.state.value.messageBusy &&
                    repository.state.value.configuredEndpoint ==
                        "http://127.0.0.1:${server.localPort}"
            }
            main {
                repository.setPolling(
                    repository.state.value.pollingState.config.copy(enabled = true)
                )
            }
            try {
                await("authentication blocked") {
                    main { repository.receivePlan() }
                    repository.state.value.pollingState.lastOutcome == SyncOutcome.AUTH
                }
            } catch (error: AssertionError) {
                fail(
                    "${error.message}; outcome=${repository.state.value.pollingState.lastOutcome}; status=${repository.state.value.realtimeStatus}"
                )
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            await("foreground removed after failure") {
                manager.activeNotifications.none { it.id == 901 }
            }
        } finally {
            main {
                repository.setPolling(
                    repository.state.value.pollingState.config.copy(enabled = false)
                )
            }
            Thread.sleep(200)
            main { repository.close() }
            activity.close()
            server.close()
            response.join(1000)
        }
    }

    @Test
    fun modeIsRuntimeOwnedAndForegroundStopsWhenReceptionIsPaused() {
        val context = instrumentation.targetContext
        val activity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        lateinit var repository: RuntimeRepository
        main {
            repository = RuntimeRepository()
            repository.start(context)
        }
        try {
            await("loaded control") {
                repository.state.value.receivePlanLoaded && !repository.state.value.messageBusy
            }
            main {
                repository.setPolling(
                    repository.state.value.pollingState.config.copy(enabled = false)
                )
            }
            await("paused") {
                !repository.state.value.messageBusy &&
                    !repository.state.value.pollingState.config.enabled
            }
            val rules = ReceivePlan(ReceiveMode.RULES, false, false, heartbeatSeconds = 180)
            main { repository.receivePlan(rules) }
            await("saved rules") { repository.state.value.receivePlan == rules }
            main {
                repository.stop()
                repository.start(context)
            }
            await("reconnected rules") {
                repository.state.value.runtimeConnected &&
                    repository.state.value.receivePlan == rules
            }
            // No rule is met: the started foreground service monitors conditions without
            // connecting.
            main { repository.transport(TransportSettings("http://127.0.0.1:9", "a".repeat(64))) }
            await("synthetic config") {
                !repository.state.value.messageBusy &&
                    repository.state.value.configuredEndpoint == "http://127.0.0.1:9"
            }
            main {
                repository.setPolling(
                    repository.state.value.pollingState.config.copy(enabled = true)
                )
            }
            await("enabled") {
                !repository.state.value.messageBusy &&
                    repository.state.value.pollingState.config.enabled
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            await("foreground notice") { manager.activeNotifications.any { it.id == 901 } }
            main {
                repository.setPolling(
                    repository.state.value.pollingState.config.copy(enabled = false)
                )
            }
            await("disabled") {
                !repository.state.value.messageBusy &&
                    !repository.state.value.pollingState.config.enabled
            }
            await("foreground removed") { manager.activeNotifications.none { it.id == 901 } }
            main { repository.receivePlan(ReceivePlan(ReceiveMode.REALTIME)) }
            await("realtime selection persisted") {
                repository.state.value.receivePlan.mode == ReceiveMode.REALTIME
            }
            assertFalse(repository.state.value.realtimeRunning)
        } finally {
            main { repository.receivePlan(ReceivePlan()) }
            Thread.sleep(200)
            main { repository.close() }
            activity.close()
        }
    }
}
