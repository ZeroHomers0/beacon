package io.github.beacon.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real :runtime/SQLite/keystore, using synthetic input and an invalid host only. */
@RunWith(AndroidJUnit4::class)
class RuntimeRepositoryTest {
    @Test
    fun previouslyScheduledWorkerStillResolvesAfterUpgrade() {
        val legacy = Class.forName("io.github.beacon.app.HttpPollingWorker")
        assertTrue(androidx.work.Worker::class.java.isAssignableFrom(legacy))
        legacy.getConstructor(
            android.content.Context::class.java,
            androidx.work.WorkerParameters::class.java,
        )
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!predicate()) {
            if (System.nanoTime() >= deadline) fail("Timed out: $description")
            Thread.sleep(25)
        }
    }

    @Test
    fun historyConfigurationAndReconnectWorkThroughTheNewRepository() {
        lateinit var repository: RuntimeRepository
        main {
            repository = RuntimeRepository()
            repository.start(instrumentation.targetContext)
        }
        try {
            // Connecting kicks off the summary, polling and first-page loads. An injection issued
            // while any of them is in flight is dropped by loadPage's busy guard, so the gate waits
            // for that connect-time work to settle rather than only for the DIAGNOSTIC status.
            await("connected history") {
                repository.state.value.runtimeConnected &&
                    repository.state.value.connectionSummaryLoaded &&
                    repository.state.value.status.kind == OperationKind.DIAGNOSTIC &&
                    !repository.state.value.messageBusy
            }
            main { repository.readMessages(injectTest = true) }
            val id = "pipeline-test-${BuildConfig.VERSION_CODE}"
            await("synthetic saved message") {
                !repository.state.value.messageBusy &&
                    repository.state.value.messages.any { it.id == id }
            }
            val message = repository.state.value.messages.first { it.id == id }
            val key = MessageKey(message.server, message.id)
            main { repository.loadDetail(message) }
            await("detail auto-read") {
                !repository.state.value.messageBusy &&
                    repository.state.value.selectedMessage?.id == id &&
                    repository.state.value.selectedMessage?.isRead == true
            }
            main {
                repository.manageMessages(
                    MessageAction.MARK_UNREAD,
                    listOf(key),
                    preservePage = true,
                )
            }
            await("mark unread") {
                !repository.state.value.messageBusy &&
                    repository.state.value.selectedMessage?.isRead == false
            }
            main { repository.manageMessages(MessageAction.DELETE, listOf(key)) }
            await("permanent deletion") {
                !repository.state.value.messageBusy &&
                    repository.state.value.messages.none { it.id == id }
            }
            val savedSettings = java.util.concurrent.atomic.AtomicReference<CleanupSettings>()
            main {
                repository.cleanupSettings(CleanupSettings(CleanupPolicy(7, 1000, false), true)) {
                    savedSettings.set(it)
                }
            }
            await("runtime-owned cleanup settings") { savedSettings.get() != null }
            assertTrue(savedSettings.get().automatic)
            savedSettings.set(null)
            main { repository.cleanupSettings(null) { savedSettings.set(it) } }
            await("read settings through Binder") { savedSettings.get() != null }
            assertEquals(CleanupPolicy(7, 1000, false), savedSettings.get().policy)
            savedSettings.set(null)
            main { repository.cleanupSettings(CleanupSettings()) { savedSettings.set(it) } }
            await("disable automatic cleanup after test") { savedSettings.get() != null }
            main { repository.previewCleanup(CleanupPolicy()) }
            await("typed storage preview through Binder") {
                !repository.state.value.messageBusy && repository.state.value.cleanupPreview != null
            }
            assertTrue(repository.state.value.cleanupPreview!!.databaseBytes > 0)
            main {
                repository.transport(
                    TransportSettings("https://refactor.example.invalid", "ab".repeat(32))
                )
            }
            await("configuration") {
                !repository.state.value.messageBusy &&
                    repository.state.value.configuredEndpoint == "https://refactor.example.invalid"
            }
            main {
                repository.setPolling(
                    repository.state.value.pollingState.config.copy(
                        enabled = false,
                        intervalMinutes = 30,
                        unmetered = true,
                    )
                )
            }
            await("polling settings") {
                !repository.state.value.messageBusy &&
                    repository.state.value.pollingState.config.intervalMinutes == 30 &&
                    repository.state.value.pollingState.config.unmetered
            }
            main {
                repository.stop()
                repository.start(instrumentation.targetContext)
            }
            await("reconnect retained messages and config") {
                repository.state.value.runtimeConnected &&
                    !repository.state.value.messageBusy &&
                    repository.state.value.connectionSummaryLoaded &&
                    repository.state.value.messages.none {
                        it.server == message.server && it.id == id
                    }
            }
            assertEquals(
                "https://refactor.example.invalid",
                repository.state.value.configuredEndpoint,
            )
        } finally {
            main { repository.close() }
        }
    }
}
