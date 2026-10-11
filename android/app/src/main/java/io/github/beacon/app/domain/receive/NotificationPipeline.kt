package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.NotificationSettings
import io.github.beacon.app.domain.model.OutdatedReminder
import io.github.beacon.app.domain.model.QuietHours
import io.github.beacon.app.domain.model.QuietHoursBehavior
import java.time.ZoneId

data class NotificationIntent(
    val sequence: Long,
    val createdAtMs: Long,
    val title: String = "",
    val body: String = "",
    val key: MessageKey? = null,
    val priority: Int = 5,
)

interface NotificationIntentStore {
    fun pendingNotifications(): List<NotificationIntent>

    fun notificationsPosted(intents: List<NotificationIntent>)
}

data class NotificationBatch(
    val tag: String,
    val count: Int,
    val alert: Boolean,
    val title: String = "",
    val body: String = "",
    val key: MessageKey? = null,
    val priority: Int = 5,
    /** Every message of this batch; a summary card can only act on them by carrying them along. */
    val keys: List<MessageKey> = emptyList(),
)

object NotificationPolicy {
    fun normalizedTimestamp(sourceMs: Long, serverMs: Long, receivedMs: Long): Long {
        if (serverMs <= 0 || serverMs < receivedMs - 300_000 || serverMs > receivedMs + 300_000)
            return 0
        if (sourceMs <= 0 || sourceMs > serverMs) return 0
        return (sourceMs + (receivedMs - serverMs)).coerceAtLeast(0)
    }

    fun batch(
        intents: List<NotificationIntent>,
        nowMs: Long,
        freshWindowMs: Long = 300_000,
    ): NotificationBatch {
        require(freshWindowMs in 60_000..86_400_000)
        require(intents.isNotEmpty() && intents.size <= 200)
        require(intents.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
        require(intents.first().sequence > 0)
        // Missing, negative, old and substantially future timestamps never justify an alert.
        val alert = intents.any {
            it.priority >= 4 &&
                it.createdAtMs > 0 &&
                it.createdAtMs >= nowMs - freshWindowMs &&
                it.createdAtMs <= nowMs
        }
        val single = intents.singleOrNull()
        return NotificationBatch(
            "beacon.batch.${intents.first().sequence}",
            intents.size,
            alert,
            single?.title.orEmpty(),
            single?.body.orEmpty(),
            single?.key,
            intents
                .filter { it.createdAtMs in (nowMs - freshWindowMs)..nowMs }
                .maxOfOrNull { it.priority } ?: 5,
            intents.mapNotNull { it.key },
        )
    }
}

/** A blocked or failed post preserves durable intents; ACK is independent. */
class NotificationPipeline(
    private val store: NotificationIntentStore,
    private val post: (NotificationBatch) -> Boolean,
    private val quietHours: QuietHours,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val settings: NotificationSettings = NotificationSettings(),
) {
    constructor(
        store: NotificationIntentStore,
        post: (NotificationBatch) -> Boolean,
    ) : this(store, post, QuietHours())

    fun flush(nowMs: Long): String {
        val intents = store.pendingNotifications()
        if (intents.isEmpty()) return ""
        settings.validate()
        val originalBatch = NotificationPolicy.batch(intents, nowMs, settings.freshWindowMs)
        val quiet = quietHours.active(nowMs, zone())
        if (quiet && quietHours.behavior == QuietHoursBehavior.SAVE_ONLY) {
            store.notificationsPosted(intents)
            return "免打扰时段：${intents.size} 条消息仅保存，不显示通知"
        }
        if (!originalBatch.alert && settings.outdatedReminder == OutdatedReminder.SAVE_ONLY) {
            store.notificationsPosted(intents)
            return "过时或时间未验证：${intents.size} 条消息仅保存，不显示通知"
        }
        val batch = if (quiet) originalBatch.copy(alert = false) else originalBatch
        if (!post(batch)) return "通知被系统权限或渠道阻止；${intents.size} 条提醒任务保留"
        store.notificationsPosted(intents)
        return if (quiet) "免打扰时段：已向系统提交静音通知"
        else if (intents.size == 1) "已向系统提交消息通知" else "已向系统提交 ${intents.size} 条消息的摘要通知"
    }
}
