package io.github.beacon.app.data.local

import android.content.Context
import io.github.beacon.app.domain.model.*
import org.json.JSONObject

/** Only user preferences; never credentials, identities, runtime results or messages. */
class SettingsBackup(private val context: Context) {
    companion object {
        const val MAX_BYTES = 4096
        private val member = """"([a-z_]+)"\s*:\s*(true|false|[0-9]{1,6}|"[a-zA-Z_]+")"""
        private val document = Regex("""\s*\{\s*$member(?:\s*,\s*$member)*\s*\}\s*""")
        private val fields = Regex(member)
        private val keys =
            setOf(
                "format",
                "version",
                "interval",
                "unmetered",
                "charging",
                "mode",
                "realtime_charging",
                "realtime_wifi",
                "fresh_minutes",
                "outdated",
                "lockscreen_preview",
                "quiet_enabled",
                "quiet_start",
                "quiet_end",
                "quiet_weekdays",
                "quiet_behavior",
                "days",
                "max_messages",
                "keep_unread",
            )

        private val ruleKeys =
            setOf(
                "schedule_enabled",
                "schedule_start",
                "schedule_end",
                "schedule_realtime",
                "low_battery_enabled",
                "low_battery_percent",
            )

        fun validate(payload: String): JSONObject {
            require(
                payload.length <= MAX_BYTES && payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES
            )
            require(document.matches(payload))
            val names = fields.findAll(payload).map { it.groupValues[1] }.toList()
            val value = JSONObject(payload)
            val version = value.opt("version")
            require(value.get("format") == "beacon_settings" && version in listOf(1, 2, 3, 4, 5))
            val expected =
                when (version) {
                    1 -> keys
                    2 -> keys + ruleKeys
                    3 -> keys + ruleKeys + "heartbeat_seconds"
                    else ->
                        (if (version == 5) keys - "lockscreen_preview" else keys) +
                            ruleKeys +
                            setOf(
                                "heartbeat_seconds",
                                "adaptive_heartbeat",
                                "batch_ordinary_messages",
                            )
                }
            require(names.size == expected.size && names.toSet() == expected)
            // Preserve the historical choice when restoring pre-heartbeat backups.
            if (version in listOf(1, 2)) value.put("heartbeat_seconds", 60)
            if (version in listOf(1, 2, 3))
                value.put("adaptive_heartbeat", false).put("batch_ordinary_messages", false)
            if (version == 1)
                value
                    .put("schedule_enabled", false)
                    .put("schedule_start", 540)
                    .put("schedule_end", 1080)
                    .put("schedule_realtime", true)
                    .put("low_battery_enabled", false)
                    .put("low_battery_percent", 20)
            fun boolean(key: String): Boolean {
                require(value.get(key) is Boolean)
                return value.getBoolean(key)
            }
            fun number(key: String): Int {
                require(value.get(key) is Int)
                return value.getInt(key)
            }
            PollingConfig(false, number("interval"), boolean("unmetered"), boolean("charging"))
                .validate()
            require(ReceiveMode.entries.any { it.key == value.get("mode") })
            boolean("realtime_charging")
            boolean("realtime_wifi")
            ReceivePlan(
                    scheduleEnabled = boolean("schedule_enabled"),
                    scheduleStart = number("schedule_start"),
                    scheduleEnd = number("schedule_end"),
                    scheduleRealtime = boolean("schedule_realtime"),
                    lowBatteryEnabled = boolean("low_battery_enabled"),
                    lowBatteryPercent = number("low_battery_percent"),
                    heartbeatSeconds = number("heartbeat_seconds"),
                    adaptiveHeartbeat = boolean("adaptive_heartbeat"),
                    batchOrdinaryMessages = boolean("batch_ordinary_messages"),
                )
                .validate()
            NotificationSettings(
                    number("fresh_minutes"),
                    OutdatedReminder.valueOf(value.getString("outdated")),
                )
                .validate()
            QuietHours(
                    boolean("quiet_enabled"),
                    number("quiet_start"),
                    number("quiet_end"),
                    number("quiet_weekdays"),
                    QuietHoursBehavior.valueOf(value.getString("quiet_behavior")),
                )
                .validate()
            CleanupPolicy(number("days"), number("max_messages"), boolean("keep_unread")).validate()
            return value
        }
    }

    fun export(): String {
        val polling = PollingStateStore(context).load().config
        val plan = ReceivePlanStore(context).load()
        val notification = NotificationSettingsStore(context).load()
        val quiet = QuietHoursStore(context).load()
        val cleanup = CleanupPreferenceStore(context).settings().policy
        return JSONObject()
            .put("format", "beacon_settings")
            .put("version", 5)
            .put("interval", polling.intervalMinutes)
            .put("unmetered", polling.unmetered)
            .put("charging", polling.charging)
            .put("mode", plan.mode.key)
            .put("realtime_charging", plan.realtimeWhileCharging)
            .put("realtime_wifi", plan.realtimeOnWifi)
            .put("schedule_enabled", plan.scheduleEnabled)
            .put("schedule_start", plan.scheduleStart)
            .put("schedule_end", plan.scheduleEnd)
            .put("schedule_realtime", plan.scheduleRealtime)
            .put("low_battery_enabled", plan.lowBatteryEnabled)
            .put("low_battery_percent", plan.lowBatteryPercent)
            .put("heartbeat_seconds", plan.heartbeatSeconds)
            .put("adaptive_heartbeat", plan.adaptiveHeartbeat)
            .put("batch_ordinary_messages", plan.batchOrdinaryMessages)
            .put("fresh_minutes", notification.freshMinutes)
            .put("outdated", notification.outdatedReminder.name)
            .put("quiet_enabled", quiet.enabled)
            .put("quiet_start", quiet.startMinute)
            .put("quiet_end", quiet.endMinute)
            .put("quiet_weekdays", quiet.weekdays)
            .put("quiet_behavior", quiet.behavior.name)
            .put("days", cleanup.days)
            .put("max_messages", cleanup.maxMessages)
            .put("keep_unread", cleanup.keepUnread)
            .toString()
            .also { validate(it) }
    }

    /**
     * Validation precedes the one commit. Import keeps configured reception automatic and leaves
     * destructive cleanup disabled.
     */
    fun restore(payload: String, current: PollingState): PollingState {
        val value = validate(payload)
        val restored =
            current.copy(
                config =
                    PollingConfig(
                        TransportSecretStore(context).isConfigured(),
                        value.getInt("interval"),
                        value.getBoolean("unmetered"),
                        value.getBoolean("charging"),
                    ),
                revision = current.revision + 1,
            )
        val editor =
            GeneralPreferences.open(context)
                .edit()
                .putString("state", PollingWire.encode(restored))
                .putBoolean("automatic", false)
        value.keys().forEach { key ->
            val target =
                when (key) {
                    "quiet_enabled" -> "enabled"
                    "quiet_start" -> "start"
                    "quiet_end" -> "end"
                    "quiet_weekdays" -> "weekdays"
                    "quiet_behavior" -> "behavior"
                    "format",
                    "lockscreen_preview",
                    "version",
                    "interval",
                    "unmetered",
                    "charging" -> null
                    else -> key
                }
            if (target != null)
                when (val field = value.get(key)) {
                    is Boolean -> editor.putBoolean(target, field)
                    is Int -> editor.putInt(target, field)
                    is String -> editor.putString(target, field)
                }
        }
        check(editor.commit())
        return restored
    }
}
