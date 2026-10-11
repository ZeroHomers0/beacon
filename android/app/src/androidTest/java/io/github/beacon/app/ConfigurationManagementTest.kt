package io.github.beacon.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.*
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ConfigurationManagementTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext

    @Test
    fun clearRemovesEncryptedCredentialsAndAllowsNewConfiguration() {
        val name = "secret-clear-${UUID.randomUUID()}"
        val isolated =
            object : ContextWrapper(base) {
                override fun getSharedPreferences(file: String, mode: Int) =
                    base.getSharedPreferences(name, mode)
            }
        try {
            val store = TransportSecretStore(isolated)
            val config = TransportSettings("https://example.invalid", "a".repeat(64))
            store.save(config)
            assertTrue(store.isConfigured())
            store.clear()
            assertFalse(TransportSecretStore(isolated).isConfigured())
            assertThrows(IllegalStateException::class.java) { store.load() }
            store.save(config)
            assertEquals(config, store.load())
        } finally {
            base.deleteSharedPreferences(name)
        }
    }

    @Test
    fun realBinderClearPausesReceptionAndPreservesHistoryAndReceipts() {
        val repository = RuntimeRepository()
        val key = MessageKey(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        RuntimeMessageStore(base).use { store ->
            store.save(IncomingMessage(key.server, key.id, "preserved", "body"))
            instrumentation.runOnMainSync { repository.start(base) }
            fun await(predicate: () -> Boolean) {
                val end = android.os.SystemClock.elapsedRealtime() + 15000
                while (!predicate() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(
                    20
                )
                assertTrue(predicate())
            }
            try {
                await {
                    repository.state.value.runtimeConnected && !repository.state.value.messageBusy
                }
                val configured = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    repository.transport(
                        TransportSettings("http://127.0.0.1:1", "b".repeat(64)),
                        onConfigured = { configured.countDown() },
                    )
                }
                assertTrue(configured.await(15, TimeUnit.SECONDS))
                await {
                    repository.state.value.configuredEndpoint == "http://127.0.0.1:1" &&
                        !repository.state.value.messageBusy
                }
                instrumentation.runOnMainSync { repository.previewCleanup(CleanupPolicy()) }
                await {
                    repository.state.value.cleanupPreview != null &&
                        !repository.state.value.messageBusy
                }
                val usage = requireNotNull(repository.state.value.cleanupPreview)
                assertTrue(usage.pendingNotifications >= 1)
                assertTrue(usage.availableBytes > 0)
                assertTrue(usage.sampledAtMs > 0)
                val cleared = CountDownLatch(1)
                instrumentation.runOnMainSync { repository.clearConnection { cleared.countDown() } }
                assertTrue(cleared.await(15, TimeUnit.SECONDS))
                assertEquals("", repository.state.value.configuredEndpoint)
                assertFalse(repository.state.value.pollingState.config.enabled)
                assertFalse(repository.state.value.realtimeRunning)
                assertNotNull(store.detail(key.server, key.id))
                assertTrue(store.pendingIds(key.server).contains(key.id))
                instrumentation.runOnMainSync { repository.refreshConnectionSummary() }
                await {
                    repository.state.value.connectionSummaryLoaded &&
                        repository.state.value.configuredEndpoint.isEmpty()
                }
            } finally {
                instrumentation.runOnMainSync { repository.close() }
                store.remove(key.server, key.id)
                store.ackCompleted(key.server, key.id)
            }
        }
    }
}
