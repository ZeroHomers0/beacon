package io.github.beacon.app.runtime

import android.content.Context
import io.github.beacon.app.data.local.NotificationSettingsStore
import io.github.beacon.app.data.local.QuietHoursStore
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.receive.NotificationIntent
import io.github.beacon.app.domain.receive.NotificationIntentStore
import io.github.beacon.app.domain.receive.NotificationPipeline
import io.github.beacon.app.runtime.notification.MessageNotifications

/** One policy snapshot per batch for HTTP, realtime and manual pending-notification handling. */
internal fun flushNotifications(context: Context, store: RuntimeMessageStore): String {
    // Asked before any work is done. With notifications off at the app or permission level the
    // pipeline could only rebuild the whole pending set and be told "no" again, once per received
    // message. The intents are durable and untouched, so raising the switch later still posts them.
    if (!MessageNotifications.postable(context)) return "通知已被系统或应用关闭；提醒任务保留，重新开启后随下一次刷新补发"
    val settings = NotificationSettingsStore(context).load()
    return NotificationPipeline(
            store,
            MessageNotifications(context, settings) { store.detail(it.server, it.id) }::post,
            QuietHoursStore(context).load(),
            settings = settings,
        )
        .flush(System.currentTimeMillis())
}

/** Reuse normal rules, but never consume another message's pending reminder. */
internal fun remindMessage(context: Context, store: RuntimeMessageStore, key: MessageKey): String {
    val intent = store.requestReminder(key) ?: return "消息已不存在，无法重新提醒"
    val single =
        object : NotificationIntentStore {
            override fun pendingNotifications() = listOf(intent)

            override fun notificationsPosted(intents: List<NotificationIntent>) =
                store.notificationsPosted(intents)
        }
    val settings = NotificationSettingsStore(context).load()
    return NotificationPipeline(
            single,
            MessageNotifications(context, settings) { store.detail(it.server, it.id) }::post,
            QuietHoursStore(context).load(),
            settings = settings,
        )
        .flush(System.currentTimeMillis())
}
