package io.github.beacon.app.runtime.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.beacon.app.MainActivity
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.NotificationSettings
import io.github.beacon.app.domain.receive.NotificationBatch

class MessageNotifications(
    private val context: Context,
    private val settings: NotificationSettings = NotificationSettings(),
    private val messageLookup:
        (
            io.github.beacon.app.domain.model.MessageKey
        ) -> io.github.beacon.app.domain.model.IncomingMessage? =
        {
            null
        },
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /**
     * Names and descriptions come from the same resources the in-app channel list shows.
     * Re-creating a channel updates its name and description, so a caller that passed a different
     * label would rename a channel the user has already configured; one source of truth prevents
     * that.
     */
    private fun channels() {
        manager.createNotificationChannel(
            NotificationChannel(
                    IMPORTANT,
                    context.getString(R.string.channel_important),
                    NotificationManager.IMPORTANCE_HIGH,
                )
                .apply {
                    description = context.getString(R.string.channel_important_desc)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                    FRESH,
                    context.getString(R.string.channel_fresh),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    description = context.getString(R.string.channel_fresh_desc)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                    HISTORY,
                    context.getString(R.string.channel_history),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description = context.getString(R.string.channel_history_desc)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
        )
    }

    fun post(batch: NotificationBatch): Boolean {
        settings.validate()
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return false
        if (!manager.areNotificationsEnabled()) return false
        // The tag carries the first sequence of the intent set and an autoincrement sequence is
        // never
        // reused, so one tag belongs to exactly one batch and the channel follows from the batch
        // alone. Enumerating activeNotifications to rediscover that would parcel every active
        // notification on each post, so it is not consulted. A later post of the same batch cannot
        // alert twice because setOnlyAlertOnce is set below.
        val channel = if (!batch.alert) HISTORY else if (batch.priority >= 8) IMPORTANT else FRESH
        // Registering a channel is a binder round-trip, and these three used to be recreated on
        // every
        // single post. Seeding them only while one is missing is equivalent after the first post
        // and
        // keeps this path down to one lookup. An existing channel is never rewritten, so a
        // description edit here cannot silently change what the user sees in system settings.
        var registered = manager.getNotificationChannel(channel)
        if (registered == null) {
            channels()
            registered = manager.getNotificationChannel(channel)
        }
        if (registered?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open =
            PendingIntent.getActivity(
                context,
                0,
                batch.key?.let { NotificationNavigation.intent(context, it) }
                    ?: Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val public =
            Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (settings.lockscreenPreview) title(batch) else "Beacon")
                .setContentText(if (settings.lockscreenPreview) body(batch) else "有新消息，请打开应用查看")
                .build()
        val notification =
            Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(
                    if (batch.count == 1) batch.title.ifBlank { "Beacon 新消息" } else "Beacon 消息摘要"
                )
                .setContentText(
                    if (batch.count == 1) batch.body else "本轮保存 ${batch.count} 条消息，点击查看"
                )
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(
                            if (batch.count == 1) batch.body else "本轮保存 ${batch.count} 条消息，点击查看"
                        )
                )
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .apply {
                    batch.key?.let { key ->
                        val intent =
                            NotificationNavigation.intent(context, key)
                                .setClass(context, NotificationReadReceiver::class.java)
                                .setAction(NotificationReadReceiver.MARK_READ)
                                .setFlags(0)
                                .putExtra(NotificationReadReceiver.TAG, batch.tag)
                        intent.data =
                            intent.data
                                ?.buildUpon()
                                ?.appendQueryParameter("notice", batch.tag)
                                ?.build()
                        val read =
                            PendingIntent.getBroadcast(
                                context,
                                0,
                                intent,
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                            )
                        addAction(Notification.Action.Builder(null, "标为已读", read).build())
                    }
                }
                .apply {
                    if (!batch.alert) {
                        setGroup("beacon.silent")
                        setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                        setSound(null)
                        setVibrate(null)
                    }
                }
                .setVisibility(
                    if (settings.lockscreenPreview) Notification.VISIBILITY_PUBLIC
                    else Notification.VISIBILITY_PRIVATE
                )
                .setPublicVersion(public)
                .setLocalOnly(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setNumber(batch.count)
                .build()
        val metadata =
            batch.key
                ?.let { messageLookup(it) }
                ?.bark
                ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        val image =
            metadata
                ?.optString("image")
                ?.takeIf { it.isNotEmpty() }
                ?.let { io.github.beacon.app.data.remote.RemoteMessageImage.load(it) }
        val icon =
            metadata
                ?.optString("icon")
                ?.takeIf { it.isNotEmpty() }
                ?.let { io.github.beacon.app.data.remote.RemoteMessageImage.load(it) }
        val presented =
            if (image != null || icon != null)
                android.app.Notification.Builder.recoverBuilder(context, notification)
                    .apply {
                        icon?.let { setLargeIcon(it) }
                        image?.let {
                            setStyle(
                                android.app.Notification.BigPictureStyle()
                                    .bigPicture(it)
                                    .setSummaryText(body(batch))
                            )
                        }
                    }
                    .build()
            else notification
        manager.notify(batch.tag, ID, presented)
        return true
    }

    private fun title(batch: NotificationBatch): String =
        if (batch.count == 1) batch.title.ifBlank { "Beacon 新消息" } else "Beacon 消息摘要"

    private fun body(batch: NotificationBatch): String =
        if (batch.count == 1) batch.body else "本轮保存 ${batch.count} 条消息，点击查看"

    companion object {
        private val FRESH = io.github.beacon.app.domain.model.NotificationChannelKind.FRESH.id
        private val IMPORTANT =
            io.github.beacon.app.domain.model.NotificationChannelKind.IMPORTANT.id
        private val HISTORY = io.github.beacon.app.domain.model.NotificationChannelKind.HISTORY.id
        internal const val ID = 100
    }
}
