package io.github.beacon.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.BindingRequest
import io.github.beacon.app.support.LoopbackServer
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pins the display rule for the foreground re-check against a healthy saved server. */
@RunWith(AndroidJUnit4::class)
class ServerAutoCheckTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val credentials =
        """{"protocol":1,"server_id":"${"b".repeat(64)}","device_id":"${"c".repeat(64)}","receive_token":"${"d".repeat(64)}","push_key":"${"e".repeat(64)}"}"""

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!predicate()) {
            if (System.nanoTime() > deadline) fail("Server probe timed out")
            Thread.sleep(25)
        }
    }

    @Test
    fun automaticRecheckKeepsTheDisplayedVerdictWhileManualShowsProgress() {
        LoopbackServer(201, credentials, enforceToken = false).use { server ->
            lateinit var repository: RuntimeRepository
            main {
                repository = RuntimeRepository()
                repository.start(instrumentation.targetContext)
            }
            try {
                await {
                    repository.state.value.runtimeConnected &&
                        repository.state.value.connectionSummaryLoaded &&
                        !repository.state.value.messageBusy
                }
                val bound = AtomicBoolean()
                main {
                    repository.bindDevice(
                        BindingRequest(server.endpoint, "a".repeat(32), "phone")
                    ) {
                        bound.set(true)
                    }
                }
                // The first probe has no earlier verdict to preserve, so it may show progress.
                await {
                    bound.get() &&
                        repository.state.value.serverReachable == true &&
                        !repository.state.value.messageBusy
                }
                val verdict = repository.state.value.serverCheckResult
                val checkedAt = repository.state.value.serverCheckedAtMs

                // The foreground re-check runs, but the toolbar keeps the healthy verdict on
                // screen:
                // startServerCheck updates the state synchronously before the probe is dispatched.
                main { repository.autoCheckServer() }
                assertFalse(repository.state.value.serverChecking)
                assertEquals(verdict, repository.state.value.serverCheckResult)
                assertEquals(checkedAt, repository.state.value.serverCheckedAtMs)
                // The probe did run: the timestamp only advances once the fresh answer lands.
                await { repository.state.value.serverCheckedAtMs > checkedAt }

                // An explicit tap still reports progress, so the user sees their request running.
                main { repository.checkServer() }
                assertTrue(repository.state.value.serverChecking)
                await {
                    !repository.state.value.serverChecking &&
                        repository.state.value.serverReachable == true
                }
            } finally {
                main { repository.close() }
            }
        }
    }
}
