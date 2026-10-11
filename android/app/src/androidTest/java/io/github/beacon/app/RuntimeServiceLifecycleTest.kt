package io.github.beacon.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.ReceiveMode
import io.github.beacon.app.domain.model.ReceivePlan
import org.junit.Assert.*
import org.junit.Test

/**
 * Verify a started foreground service outlives UI bindings in the single app process.
 */
class RuntimeServiceLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Timed out: $label")
            Thread.sleep(100)
        }
    }

    @Test
    fun closingUiPreservesForegroundServiceAndRebindingLoadsDurableControl() {
        val context = instrumentation.targetContext
        val activity = ActivityScenario.launch(MainActivity::class.java)
        lateinit var repository: RuntimeRepository
        main {
            repository = RuntimeRepository()
            repository.start(context)
        }
        try {
            await("loaded") {
                repository.state.value.receivePlanLoaded && !repository.state.value.messageBusy
            }
            // A fresh install has no credentials and correctly refuses foreground reception.
            // Use a loopback endpoint with no listener: transport failure is retryable, no user server.
            main {
                repository.transport(
                    io.github.beacon.app.domain.model.TransportSettings("http://127.0.0.1:9", "a".repeat(64))
                )
            }
            await("test transport saved") {
                repository.state.value.configuredEndpoint == "http://127.0.0.1:9" &&
                    !repository.state.value.messageBusy
            }
            main { repository.receivePlan(ReceivePlan(ReceiveMode.RULES, false, false)) }
            await("rules") {
                repository.state.value.receivePlan.mode == ReceiveMode.RULES &&
                    !repository.state.value.messageBusy
            }
            main {
                repository.setPolling(
                    repository.state.value.pollingState.config.copy(enabled = true)
                )
            }
            await("enabled") {
                repository.state.value.pollingState.config.enabled &&
                    !repository.state.value.messageBusy
            }
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            @Suppress("DEPRECATION")
            fun running() = manager.getRunningServices(100).firstOrNull {
                it.service.className == io.github.beacon.app.runtime.RuntimeProbeService::class.java.name
            }
            await("foreground started") { running()?.foreground == true }
            assertEquals(android.os.Process.myPid(), running()!!.pid)
            main { repository.stop() }
            activity.close()
            // No diagnostic binding keeps the service alive: it is independently started.
            Thread.sleep(500)
            assertTrue(running()?.foreground == true)
            main { repository.start(context) }
            await("rebound durable control") {
                repository.state.value.runtimeConnected &&
                    repository.state.value.receivePlanLoaded &&
                    !repository.state.value.messageBusy
            }
            assertTrue(repository.state.value.pollingState.config.enabled)
            main {
                repository.setPolling(
                    repository.state.value.pollingState.config.copy(enabled = false)
                )
            }
            await("pause persisted") {
                !repository.state.value.pollingState.config.enabled &&
                    !repository.state.value.messageBusy
            }
        } finally {
            main { repository.stop() }
            activity.close()
        }
    }
}
