package io.github.beacon.app

import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.*
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.receive.NotificationIntent
import io.github.beacon.app.domain.receive.NotificationIntentStore
import io.github.beacon.app.domain.receive.NotificationPipeline
import io.github.beacon.app.runtime.remindMessage
import io.github.beacon.app.ui.BeaconUiState
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class MessageOperationsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun key() = MessageKey(UUID.randomUUID().toString(), UUID.randomUUID().toString())

    @Test
    fun duplicateReminderKeepsSingleIntentAndDoesNotChangeReadOrConfirmation() {
        val key = key()
        RuntimeMessageStore(context).use { store ->
            store.save(
                IncomingMessage(
                    key.server,
                    key.id,
                    "title",
                    "body",
                    createdAtMs = System.currentTimeMillis(),
                )
            )
            try {
                val first = store.requestReminder(key)!!
                val second = store.requestReminder(key)!!
                assertEquals(first.sequence, second.sequence)
                assertEquals(1, store.pendingNotifications().count { it.key == key })
                assertFalse(store.detail(key.server, key.id)!!.isRead)
                assertTrue(store.pendingIds(key.server).contains(key.id))
                val adapter =
                    object : NotificationIntentStore {
                        override fun pendingNotifications() = listOf(first)

                        override fun notificationsPosted(intents: List<NotificationIntent>) =
                            store.notificationsPosted(intents)
                    }
                NotificationPipeline(adapter, { false }).flush(System.currentTimeMillis())
                assertEquals(first.sequence, store.requestReminder(key)!!.sequence)
                store.remove(key.server, key.id)
                assertNull(store.requestReminder(key))
            } finally {
                store.remove(key.server, key.id)
            }
        }
    }

    @Test
    fun outdatedSaveOnlyConsumesOnlyRequestedMessageAndNotOtherPendingIntents() {
        val key = key()
        val other = key()
        val settings = NotificationSettingsStore(context)
        val original = settings.load()
        val quiet = QuietHoursStore(context)
        val originalQuiet = quiet.load()
        RuntimeMessageStore(context).use { store ->
            store.save(IncomingMessage(key.server, key.id, "requested", "body", createdAtMs = 1))
            store.save(IncomingMessage(other.server, other.id, "other", "body", createdAtMs = 1))
            try {
                settings.save(NotificationSettings(outdatedReminder = OutdatedReminder.SAVE_ONLY))
                quiet.save(QuietHours())
                assertTrue(remindMessage(context, store, key).contains("仅保存"))
                assertFalse(store.pendingNotifications().any { it.key == key })
                assertTrue(store.pendingNotifications().any { it.key == other })
                assertFalse(store.detail(key.server, key.id)!!.isRead)
                assertTrue(store.pendingIds(key.server).contains(key.id))
            } finally {
                settings.save(original)
                quiet.save(originalQuiet)
                store.remove(key.server, key.id)
                store.remove(other.server, other.id)
            }
        }
    }

    @Test
    fun savedDetailKeyRestoresAndExplicitBackClearsIt() {
        val key = key()
        val handle = SavedStateHandle(mapOf("detailKey" to key, "page" to 0))
        val actions = RuntimeRepository()
        val restored = BeaconUiState(handle, UiStyleStore(context))
        assertEquals(key, restored.pendingNotification)
        instrumentation.runOnMainSync {
            restored.closeDetail(actions)
            actions.close()
        }
        val next = BeaconUiState(handle, UiStyleStore(context))
        assertNull(next.pendingNotification)
        assertNull(next.detailKey)
        val notified = key()
        val pending =
            BeaconUiState(
                SavedStateHandle(mapOf("detailKey" to key, "pendingNotification" to notified)),
                UiStyleStore(context),
            )
        assertEquals(notified, pending.pendingNotification)
    }

    @Test
    fun reminderMissingMessageRunsThroughRealRuntimeBinder() {
        val repository = RuntimeRepository()
        instrumentation.runOnMainSync { repository.start(context) }
        fun await(predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (!predicate() && android.os.SystemClock.elapsedRealtime() < deadline) Thread
                .sleep(25)
            assertTrue(predicate())
        }
        try {
            await { repository.state.value.runtimeConnected && !repository.state.value.messageBusy }
            instrumentation.runOnMainSync { repository.remindMessage(key()) }
            await {
                repository.state.value.status.text.contains("消息已不存在") &&
                    !repository.state.value.messageBusy
            }
        } finally {
            instrumentation.runOnMainSync { repository.close() }
        }
    }
}
