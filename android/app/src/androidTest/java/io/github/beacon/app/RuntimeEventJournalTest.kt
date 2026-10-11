package io.github.beacon.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeEventStore
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.diagnostics.AndroidRuntimeEventText
import org.junit.Assert.*
import org.junit.Test

class RuntimeEventJournalTest {
    private fun isolated(block: (android.content.Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "event-test-${java.util.UUID.randomUUID()}"
        val context =
            object : ContextWrapper(base) {
                override fun getSharedPreferences(file: String, mode: Int) =
                    base.getSharedPreferences(name, mode)
            }
        try {
            block(context)
        } finally {
            base.deleteSharedPreferences(name)
        }
    }

    @Test
    fun persistedHistoryIsBoundedAndClearDoesNotDisableRecording() = isolated { context ->
        val store = RuntimeEventStore(context)
        repeat(125) {
            store.append(RuntimeEvent(1000L + it, RuntimeEventKind.MANUAL_SYNC, SyncOutcome.OK))
        }
        val restored = RuntimeEventStore(context).read()
        assertEquals(100, restored.size)
        assertEquals(1025L, restored.first().atMs)
        assertEquals(1124L, restored.last().atMs)
        store.clear()
        assertTrue(RuntimeEventStore(context).read().isEmpty())
        store.append(RuntimeEvent(2000, RuntimeEventKind.RECEIVE_PAUSED))
        assertEquals(1, RuntimeEventStore(context).read().size)
    }

    @Test
    fun corruptJournalIsRejectedAndNewEventRepairsWithoutExportingInjectedText() =
        isolated { context ->
            val prefs = context.getSharedPreferences("ignored", 0)
            prefs
                .edit()
                .putString(
                    "events",
                    "[{\"at\":1,\"kind\":999,\"outcome\":0,\"failure\":\"SECRET_SENTINEL\"}]",
                )
                .commit()
            val store = RuntimeEventStore(context)
            assertThrows(IllegalArgumentException::class.java) { store.read() }
            store.append(RuntimeEvent(2, RuntimeEventKind.SERVICE_STARTED))
            assertEquals(1, store.read().size)
            assertFalse(
                AndroidRuntimeEventText(context).export(store.read()).contains("SECRET_SENTINEL")
            )
            assertFalse(prefs.getString("events", "")!!.contains("SECRET_SENTINEL"))
        }
}
