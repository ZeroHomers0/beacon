package io.github.beacon.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.ReceiveMode
import io.github.beacon.app.domain.model.ReceivePlan
import org.junit.Assert.*
import org.junit.Test

/**
 * Kill only the test app's own worker, then observe real system restart and Binder reconnection.
 */
class RuntimeCrashRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Timed out: $label")
            Thread.sleep(100)
        }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it)
                .bufferedReader()
                .readText()
                .trim()
        }

    @Test
    fun workerKillRestoresServiceAndVisibleClient() {
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
            val process = "${context.packageName}:runtime"
            val original = shell("pidof $process").toInt()
            require(original > 0 && original != android.os.Process.myPid())
            shell("run-as ${context.packageName} kill -9 $original")
            await("real worker restart") {
                shell("pidof $process").let { it.isNotEmpty() && it != original.toString() }
            }
            await("Binder reconnect and recorded attempt") {
                repository.state.value.runtimeConnected &&
                    repository.state.value.recovery.attempts > 0 &&
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
