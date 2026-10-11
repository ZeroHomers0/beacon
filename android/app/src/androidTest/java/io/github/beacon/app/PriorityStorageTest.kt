package io.github.beacon.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageAction
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.MessageMutation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PriorityStorageTest {
    @Test
    fun saveOnlyAndImportantPrioritiesSurviveRealSqliteReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "priority-fixture"
        val suffix = java.util.UUID.randomUUID().toString()
        val silent =
            IncomingMessage(namespace, "silent-$suffix", "saved only", "body", priority = 0)
        val important =
            IncomingMessage(namespace, "important-$suffix", "important", "body", priority = 8)
        RuntimeMessageStore(context).use { store ->
            store.save(silent)
            store.save(important)
        }
        RuntimeMessageStore(context).use { store ->
            try {
                val intents = store.pendingNotifications().filter { it.key?.server == namespace }
                assertFalse(intents.any { it.key?.id == silent.id })
                assertEquals(8, intents.single { it.key?.id == important.id }.priority)
                assertEquals(0, store.detail(namespace, silent.id)?.priority)
                assertEquals(8, store.detail(namespace, important.id)?.priority)
            } finally {
                store.mutate(
                    MessageMutation(
                        MessageAction.DELETE,
                        listOf(
                            MessageKey(namespace, silent.id),
                            MessageKey(namespace, important.id),
                        ),
                    )
                )
            }
        }
    }
}
