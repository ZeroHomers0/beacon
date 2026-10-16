package io.github.beacon.app.runtime.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import io.github.beacon.app.MainActivity
import io.github.beacon.app.R
import io.github.beacon.app.data.remote.MessageActionsCodec
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageLinkAction
import io.github.beacon.app.domain.model.NotificationSettings
import io.github.beacon.app.domain.policy.MessageMarkdown
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
        val summary = body(batch)
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
        val message = batch.key?.let { runCatching { messageLookup(it) }.getOrNull() }
        val noticeActions = buildList {
            readAction(batch)?.let { add(it) }
            batch.key
                ?.takeIf { batch.body.isNotBlank() }
                ?.let { add(copyAction(it, batch.tag, batch.body)) }
            addAll(linkActions(message, 3 - size))
        }
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
                .setContentText(if (settings.lockscreenPreview) summary else "有新消息，请打开应用查看")
                .build()
        val notification =
            Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(
                    if (batch.count == 1) batch.title.ifBlank { "Beacon 新消息" } else "Beacon 消息摘要"
                )
                .setContentText(summary)
                .setStyle(Notification.BigTextStyle().bigText(summary))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .apply {
                    noticeActions.forEach { addAction(it) }
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
        // Media is resolved only for a card that can actually alert. A silent batch — the
        // quiet-hours downgrade and the history channel included — is built without touching the
        // network, and [RemoteMessageImage] keeps the alert path to one cold fetch per URL.
        val metadata =
            if (batch.alert)
                message?.bark?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            else null
        // Only the picture is fetched. The avatar's setLargeIcon cost a second cold handshake and a
        // second decode for a 48 dp thumbnail, and the card is already identified by its small icon
        // and title; the detail page still shows the avatar.
        val image =
            metadata
                ?.optString("image")
                ?.takeIf { it.isNotEmpty() }
                ?.let { io.github.beacon.app.data.remote.RemoteMessageImage.load(it) }
        val presented =
            if (image != null)
                android.app.Notification.Builder.recoverBuilder(context, notification)
                    .setStyle(
                        android.app.Notification.BigPictureStyle()
                            .bigPicture(image)
                            .setSummaryText(summary)
                    )
                    .build()
            else notification
        manager.notify(batch.tag, ID, presented)
        return true
    }

    private fun title(batch: NotificationBatch): String =
        if (batch.count == 1) batch.title.ifBlank { "Beacon 新消息" } else "Beacon 消息摘要"

    private fun body(batch: NotificationBatch): String =
        if (batch.count == 1) MessageMarkdown.summary(batch.body, 300)
        else "本轮保存 ${batch.count} 条消息，点击查看"

    /**
     * The single-message read plus the whole-batch read, both handled by the same non-exported
     * receiver so the state change stays one local transaction.
     */
    private fun readAction(batch: NotificationBatch): Notification.Action? {
        val key = batch.key
        if (key == null && batch.keys.isEmpty()) return null
        val intent =
            if (key != null)
                actionIntent(key, batch.tag)
                    .setClass(context, NotificationReadReceiver::class.java)
                    .setAction(NotificationReadReceiver.MARK_READ)
            else
                Intent(context, NotificationReadReceiver::class.java)
                    .setAction(NotificationReadReceiver.MARK_ALL_READ)
                    .setData(noticeData(batch.tag))
                    .putStringArrayListExtra(
                        NotificationReadReceiver.SERVERS,
                        ArrayList(batch.keys.map { it.server }),
                    )
                    .putStringArrayListExtra(
                        NotificationReadReceiver.IDS,
                        ArrayList(batch.keys.map { it.id }),
                    )
        val read =
            PendingIntent.getBroadcast(
                context,
                0,
                intent.putExtra(NotificationReadReceiver.TAG, batch.tag),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val label = if (key != null) R.string.action_mark_read else R.string.action_mark_all_read
        val builder = Notification.Action.Builder(null, context.getString(label), read)
        // A label alone does not tell the platform what an action means; the semantic does. Both
        // constants only exist from API 28 on.
        if (Build.VERSION.SDK_INT >= 28) {
            builder.setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
        }
        return builder.build()
    }

    private fun copyAction(
        key: io.github.beacon.app.domain.model.MessageKey,
        tag: String,
        text: String,
    ): Notification.Action {
        val intent =
            actionIntent(key, tag)
                .setClass(context, NotificationCopyReceiver::class.java)
                .setAction(NotificationCopyReceiver.COPY_BODY)
                .putExtra(NotificationCopyReceiver.TEXT, text)
        val copy =
            PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification.Action.Builder(null, context.getString(R.string.action_copy_body), copy)
            .build()
    }

    private fun linkActions(message: IncomingMessage?, limit: Int): List<Notification.Action> {
        if (message == null || limit <= 0) return emptyList()
        val available = runCatching {
            message.actions
                .ifEmpty {
                    val url = runCatching {
                        org.json.JSONObject(message.bark).optString("url")
                    }.getOrDefault("")
                    if (url.isBlank()) emptyList()
                    else
                        listOf(
                            MessageLinkAction(context.getString(R.string.message_action_open), url)
                        )
                }
                .also(MessageActionsCodec::validate)
        }
            .getOrDefault(emptyList())
        return available
            .distinctBy { it.url }
            .take(limit)
            .map { action ->
                val intent =
                    Intent(Intent.ACTION_VIEW, Uri.parse(action.url).normalizeScheme())
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val open =
                    PendingIntent.getActivity(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                Notification.Action.Builder(null, action.title, open).build()
            }
    }

    private fun actionIntent(
        key: io.github.beacon.app.domain.model.MessageKey,
        tag: String,
    ): Intent {
        val intent = NotificationNavigation.intent(context, key).setFlags(0)
        intent.data = intent.data?.buildUpon()?.appendQueryParameter("notice", tag)?.build()
        return intent
    }

    /**
     * A PendingIntent compares equal when its action, data, type, class and categories match, and
     * ignores extras. The notice tag therefore has to travel in the data URI, or a later batch for
     * the same message would reuse an earlier card's action extras.
     */
    private fun noticeData(tag: String): Uri =
        Uri.Builder()
            .scheme("beacon")
            .authority("batch")
            .appendQueryParameter("notice", tag)
            .build()

    companion object {
        private val FRESH = io.github.beacon.app.domain.model.NotificationChannelKind.FRESH.id
        private val IMPORTANT =
            io.github.beacon.app.domain.model.NotificationChannelKind.IMPORTANT.id
        private val HISTORY = io.github.beacon.app.domain.model.NotificationChannelKind.HISTORY.id
        internal const val ID = 100

        /**
         * The verdict `post` would reach anyway, asked before a caller renders a whole batch. While
         * notifications are off at the app or permission level the post can only fail, so reading
         * the pending set, parsing settings and building the card for every received message bought
         * nothing. The result is not cached: a stale denial would defer — and by the fresh-window
         * rule eventually silence — a real alert, which is worse than one binder call per message.
         */
        fun postable(context: Context): Boolean {
            if (
                Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
            )
                return false
            return context
                .getSystemService(NotificationManager::class.java)
                .areNotificationsEnabled()
        }
    }
}
