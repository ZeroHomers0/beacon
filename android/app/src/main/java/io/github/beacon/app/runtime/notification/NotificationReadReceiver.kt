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
        val tag =
            intent.getStringExtra(TAG)?.takeIf { it.startsWith("beacon.batch.") && it.length <= 80 }
                ?: return
        // The single-message card carries one key in the data URI; a summary card has no single
        // key, so it hands the whole batch over in extras.
        val keys =
            when (intent.action) {
                MARK_READ -> listOfNotNull(NotificationNavigation.key(intent))
                MARK_ALL_READ -> batchKeys(intent)
                else -> return
            }
        if (keys.isEmpty()) return
        val pending = goAsync()
        executor.execute {
            try {
                RuntimeMessageStore(context).use { store ->
                    if (store.readFromNotification(keys) > 0) revision.incrementAndGet()
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

    /** A summary card stands for several messages; anything malformed reads as no keys at all. */
    private fun batchKeys(intent: Intent): List<MessageKey> {
        val servers = intent.getStringArrayListExtra(SERVERS) ?: return emptyList()
        val ids = intent.getStringArrayListExtra(IDS) ?: return emptyList()
        if (servers.isEmpty() || servers.size != ids.size || servers.size > MESSAGE_LIMIT)
            return emptyList()
        return runCatching {
                servers.indices.map { index ->
                    MessageKey(servers[index].orEmpty(), ids[index].orEmpty()).also { it.validate() }
                }
            }
            .getOrNull()
            .orEmpty()
    }

    companion object {
        internal const val MARK_READ = "io.github.beacon.app.notification.MARK_READ"
        internal const val MARK_ALL_READ = "io.github.beacon.app.notification.MARK_ALL_READ"
        internal const val TAG = "notification_tag"
        internal const val SERVERS = "notification_servers"
        internal const val IDS = "notification_ids"
        internal val revision = AtomicLong()
        private const val MESSAGE_LIMIT = 200
        private val executor = serialExecutor("beacon-notification-read")
    }
}
