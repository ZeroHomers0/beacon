package io.github.beacon.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.runtime.RuntimeUiEvents
import io.github.beacon.app.runtime.notification.NotificationNavigation
import io.github.beacon.app.runtime.notification.NotificationReadReceiver
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundRuntimeEventsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(label: String, timeoutMs: Long = 10_000, predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate()) {
            if (android.os.SystemClock.elapsedRealtime() >= deadline) fail("Timed out: $label")
            Thread.sleep(20)
        }
    }

    @Test
    fun burstHintsCoalesceAndUnobservedRuntimeStaysQuiet() {
        val calls = AtomicInteger()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    calls.incrementAndGet()
                }
            }
        main {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(RuntimeUiEvents.ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            RuntimeUiEvents.observe(context, true)
            repeat(100) { RuntimeUiEvents.changed(context) }
        }
        try {
            await("single merged hint", 2000) { calls.get() == 1 }
            Thread.sleep(400)
            assertEquals(1, calls.get())
            main {
                RuntimeUiEvents.changed(context)
                RuntimeUiEvents.observe(context, false)
                repeat(100) { RuntimeUiEvents.changed(context) }
            }
            Thread.sleep(600)
            assertEquals("Unbinding must cancel the queued hint", 1, calls.get())
        } finally {
            main {
                RuntimeUiEvents.observe(context, false)
                context.unregisterReceiver(receiver)
            }
        }
    }

    @Test
    fun notificationReadRefreshesBoundRepositoryWithoutAUiPollingLoopAndRebindRecovers() {
        val namespace = "foreground-event-test"
        val ids = (1..2).map { "ui-event-${java.util.UUID.randomUUID()}" }
        RuntimeMessageStore(context).use { store ->
            ids.forEach {
                store.save(IncomingMessage(namespace, it, "UI event", "synthetic", priority = 0))
            }
        }
        lateinit var repository: RuntimeRepository
        main {
            repository = RuntimeRepository()
            repository.start(context)
        }
        fun markRead(id: String) {
            val intent =
                NotificationNavigation.intent(context, MessageKey(namespace, id))
                    .setClass(context, NotificationReadReceiver::class.java)
                    .setAction(NotificationReadReceiver.MARK_READ)
                    .putExtra(NotificationReadReceiver.TAG, "beacon.batch.ui-event-test")
            intent.flags = 0
            context.sendBroadcast(intent)
        }
        fun isRead(id: String) = repository.state.value.messages.any { it.id == id && it.isRead }
        try {
            await("initial page") {
                repository.state.value.runtimeConnected &&
                    repository.state.value.receivePlanLoaded &&
                    !repository.state.value.messageBusy &&
                    repository.state.value.messages.any { it.id == ids[0] }
            }
            // There is no Activity/BeaconApp here, hence no periodic UI refresh at all.
            markRead(ids[0])
            await("event-driven read update", 4000) { isRead(ids[0]) }
            main { repository.stop() }
            markRead(ids[1])
            await("runtime read while UI disconnected") {
                RuntimeMessageStore(context).use { it.detail(namespace, ids[1])?.isRead == true }
            }
            main { repository.start(context) }
            await("rebound snapshot") { repository.state.value.runtimeConnected && isRead(ids[1]) }
        } finally {
            main { repository.close() }
            RuntimeMessageStore(context).use { store ->
                for (table in
                    listOf("messages", "pending_ack", "receipt_times", "notification_intents")) {
                    store.writableDatabase.delete(table, "server=?", arrayOf(namespace))
                }
            }
        }
    }
}
