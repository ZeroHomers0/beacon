package io.github.beacon.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.MessageQuery
import io.github.beacon.app.domain.model.MessageReadFilter
import org.junit.Assert.*
import org.junit.Test

class ContinuousHistoryTest {
    @Test
    fun workerPagesAppendWithoutDuplicatesAndRefreshResets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val namespace = "continuous-${java.util.UUID.randomUUID()}"
        val store = RuntimeMessageStore(context)
        lateinit var repository: RuntimeRepository
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun await(predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (!predicate()) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "worker timed out" }
                Thread.sleep(20)
            }
        }
        try {
            store.writableDatabase.execSQL(
                """WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<650)
    INSERT INTO messages(server,id,title,body,source_created,received,is_read)
    SELECT ?,'row-'||x,'Title '||x,'History',x,x,x%2 FROM n""",
                arrayOf(namespace),
            )
            main {
                repository = RuntimeRepository()
                repository.start(context)
            }
            await {
                repository.state.value.runtimeConnected &&
                    repository.state.value.connectionSummaryLoaded &&
                    !repository.state.value.messageBusy
            }
            main { repository.readMessages(serverId = namespace, showStatus = false) }
            await {
                !repository.state.value.messageBusy &&
                    repository.state.value.messageQuery.serverId == namespace
            }
            assertEquals(MessageQuery.PAGE_SIZE, repository.state.value.messages.size)
            main { repository.nextMessages() }
            await {
                !repository.state.value.messageBusy && repository.state.value.messagePageIndex == 1
            }
            assertEquals(MessageQuery.PAGE_SIZE * 2, repository.state.value.messages.size)
            while (repository.state.value.nextMessageCursor != null) {
                val page = repository.state.value.messagePageIndex
                main { repository.nextMessages() }
                await {
                    !repository.state.value.messageBusy &&
                        repository.state.value.messagePageIndex > page
                }
                val messages = repository.state.value.messages
                assertTrue(messages.size <= 500)
                assertEquals(messages.size, messages.map { it.id }.toSet().size)
            }
            assertEquals(500, repository.state.value.messages.size)
            main {
                repository.readMessages(
                    serverId = namespace,
                    readFilter = MessageReadFilter.READ,
                    showStatus = false,
                )
            }
            await {
                !repository.state.value.messageBusy &&
                    repository.state.value.messageQuery.readFilter == MessageReadFilter.READ
            }
            assertEquals(MessageQuery.PAGE_SIZE, repository.state.value.messages.size)
            assertTrue(repository.state.value.messages.all { it.isRead })
            assertEquals(0L, repository.state.value.messageUnread)
        } finally {
            main { repository.close() }
            store.writableDatabase.delete("messages", "server=?", arrayOf(namespace))
            store.close()
        }
    }
}
