package io.github.beacon.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.remote.BindingRejected
import io.github.beacon.app.data.remote.BindingResponseInvalid
import io.github.beacon.app.data.remote.HttpDeviceBinding
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.BindingRequest
import io.github.beacon.app.domain.model.TransportSettings
import io.github.beacon.app.support.LoopbackServer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceBindingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(predicate: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (!predicate()) {
            if (System.nanoTime() > end) fail("Binding operation timed out")
            Thread.sleep(25)
        }
    }

    private val credentials =
        """{"protocol":1,"server_id":"${"b".repeat(64)}","device_id":"${"c".repeat(64)}","receive_token":"${"d".repeat(64)}","push_key":"${"e".repeat(64)}"}"""

    @Test
    fun bindsThroughWorkerEncryptsAndCopiesPushAddress() {
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
                val completed = AtomicBoolean()
                main {
                    repository.bindDevice(
                        BindingRequest(server.endpoint, "a".repeat(32), "phone")
                    ) {
                        completed.set(true)
                    }
                }
                await {
                    completed.get() &&
                        repository.state.value.serverReachable == true &&
                        !repository.state.value.serverChecking &&
                        !repository.state.value.messageBusy
                }
                assertEquals(server.endpoint, repository.state.value.configuredEndpoint)
                assertTrue(repository.state.value.serverReachable == true)
                assertTrue(server.posted.get().contains("\"binding_code\":\"${"a".repeat(32)}\""))
                val copied = AtomicReference<String>()
                main { repository.copyPushKey { copied.set(it) } }
                await { copied.get() != null }
                assertEquals("e".repeat(64), copied.get())
                val saved = AtomicBoolean()
                main {
                    repository.transport(
                        TransportSettings(server.endpoint, ""),
                        onConfigured = { saved.set(true) },
                    )
                }
                await { saved.get() && !repository.state.value.messageBusy }
                copied.set(null)
                main { repository.copyPushKey { copied.set(it) } }
                await { copied.get() != null }
                assertEquals("e".repeat(64), copied.get())
            } finally {
                main { repository.close() }
            }
        }
    }

    @Test
    fun rejectedBindingKeepsExistingConfiguration() {
        LoopbackServer(400, "{}", enforceToken = false).use { server ->
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
                val original = repository.state.value.configuredEndpoint
                val callback = AtomicBoolean()
                main {
                    repository.bindDevice(
                        BindingRequest(server.endpoint, "a".repeat(32), "phone")
                    ) {
                        callback.set(true)
                    }
                }
                await {
                    !repository.state.value.messageBusy &&
                        repository.state.value.status.text.contains("绑定码无效")
                }
                assertFalse(callback.get())
                assertEquals(original, repository.state.value.configuredEndpoint)
            } finally {
                main { repository.close() }
            }
        }
    }

    @Test
    fun protocolFailureDoesNotProduceCredentials() {
        LoopbackServer(201, """{"protocol":2}""", enforceToken = false).use { server ->
            assertThrows(BindingResponseInvalid::class.java) {
                HttpDeviceBinding().bind(BindingRequest(server.endpoint, "a".repeat(32), "phone"))
            }
        }
    }

    @Test
    fun redirectsAreRejected() {
        LoopbackServer(302, "{}", enforceToken = false).use { server ->
            val error =
                assertThrows(BindingRejected::class.java) {
                    HttpDeviceBinding()
                        .bind(BindingRequest(server.endpoint, "a".repeat(32), "phone"))
                }
            assertEquals(302, error.status)
        }
    }

    @Test
    fun expiredRequestBudgetDoesNotStartExchange() {
        assertThrows(java.net.SocketTimeoutException::class.java) {
            HttpDeviceBinding()
                .bind(
                    BindingRequest("https://example.invalid", "a".repeat(32), "phone"),
                    System.nanoTime() - 1,
                )
        }
    }
}
