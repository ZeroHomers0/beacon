package io.github.beacon.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupReadingAnchorTest {
    @Test fun releasedReadingWindowRestoresItsAnchorAfterControlRefreshes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val server = "startup-anchor-${java.util.UUID.randomUUID()}"
        RuntimeMessageStore(context).use { store ->
            repeat(45) { index ->
                store.save(IncomingMessage(server, "message-$index", "Anchor $index", "Saved body", receivedAtMs = System.currentTimeMillis() + index))
            }
        }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun await(label: String, predicate: () -> Boolean) {
            val end = System.nanoTime() + 15_000_000_000L
            while (!predicate()) {
                if (System.nanoTime() > end) fail("Timed out: $label")
                Thread.sleep(20)
            }
        }
        lateinit var repository: RuntimeRepository
        main { repository = RuntimeRepository().also { it.start(context) } }
        try {
            await("ready") { repository.state.value.messagesLoaded && repository.state.value.receivePlanLoaded && !repository.state.value.messageBusy }
            main { repository.readMessages(serverId = server) }
            await("filtered history") { !repository.state.value.messageBusy && repository.state.value.messageTotal == 45L }
            main { repository.nextMessages() }
            await("second page") { !repository.state.value.messageBusy && repository.state.value.messagePageIndex == 1 }
            val first = repository.state.value.messages.first { it.id == "message-10" }
            val anchor = MessageKey(first.server, first.id)
            main {
                repository.saveReadingAnchor(anchor)
                repository.stop()
                repository.releaseBackgroundDetail()
                repository.start(context)
            }
            await("restored anchored page") { repository.state.value.messagesLoaded && !repository.state.value.messageBusy && repository.state.value.messages.any { it.id == anchor.id && it.server == anchor.server } }
            assertEquals(server, repository.state.value.messageQuery.serverId)
            assertEquals(anchor.id, repository.state.value.messages.first().id)
            // Force the next real runtime control response to report a changed delivery revision.
            // This exercises the refresh that previously jumped a restored window to the newest page.
            val stateField = RuntimeRepository::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val mutable = stateField.get(repository) as kotlinx.coroutines.flow.MutableStateFlow<io.github.beacon.app.domain.model.RuntimeState>
            main {
                mutable.value = mutable.value.copy(deliveryRevision = -1)
                repository.refreshPolling()
                repository.receivePlan()
            }
            await("changed revision settled") { repository.state.value.deliveryRevision != -1L && !repository.state.value.messageBusy }
            assertEquals(anchor.id, repository.state.value.messages.first().id)
        } finally {
            main { repository.close() }
        }
    }
}
