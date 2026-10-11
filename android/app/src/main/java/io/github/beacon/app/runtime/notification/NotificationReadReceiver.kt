package io.github.beacon.app.runtime.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.beacon.app.core.serialExecutor
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.*
import java.util.concurrent.atomic.AtomicLong

/** Non-exported, runtime-owned action. Finish the read transaction before removing the notice. */
class NotificationReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MARK_READ) return
        val key = NotificationNavigation.key(intent) ?: return
        val tag =
            intent.getStringExtra(TAG)?.takeIf { it.startsWith("beacon.batch.") && it.length <= 80 }
                ?: return
        val pending = goAsync()
        executor.execute {
            try {
                RuntimeMessageStore(context).use { store ->
                    if (store.readFromNotification(key) > 0) revision.incrementAndGet()
                }
                context
                    .getSystemService(NotificationManager::class.java)
                    .cancel(tag, MessageNotifications.ID)
            } catch (_: Exception) {
                // Leave the notification available for retry; never log message identity or body.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        internal const val MARK_READ = "io.github.beacon.app.notification.MARK_READ"
        internal const val TAG = "notification_tag"
        internal val revision = AtomicLong()
        private val executor = serialExecutor("beacon-notification-read")
    }
}
