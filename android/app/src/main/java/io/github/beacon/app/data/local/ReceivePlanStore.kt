package io.github.beacon.app.data.local

import android.content.Context
import io.github.beacon.app.domain.model.ReceiveMode
import io.github.beacon.app.domain.model.ReceivePlan

/** Persists mode selection and future realtime conditions independently of polling constraints. */
class ReceivePlanStore(context: Context) {
    private val preferences = GeneralPreferences.open(context)

    fun load() =
        ReceivePlan(
                mode = ReceiveMode.fromKey(preferences.getString("mode", null)),
                realtimeWhileCharging = preferences.getBoolean("realtime_charging", true),
                realtimeOnWifi = preferences.getBoolean("realtime_wifi", true),
                scheduleEnabled = preferences.getBoolean("schedule_enabled", false),
                scheduleStart = preferences.getInt("schedule_start", 540),
                scheduleEnd = preferences.getInt("schedule_end", 1080),
                scheduleRealtime = preferences.getBoolean("schedule_realtime", true),
                lowBatteryEnabled = preferences.getBoolean("low_battery_enabled", false),
                lowBatteryPercent = preferences.getInt("low_battery_percent", 20),
                heartbeatSeconds =
                    preferences.getInt(
                        "heartbeat_seconds",
                        io.github.beacon.app.domain.model.RealtimeHeartbeat.DEFAULT_SECONDS,
                    ),
            )
            .also { it.validate() }

    fun save(plan: ReceivePlan) {
        plan.validate()
        check(
            preferences
                .edit()
                .putString("mode", plan.mode.key)
                .putBoolean("realtime_charging", plan.realtimeWhileCharging)
                .putBoolean("realtime_wifi", plan.realtimeOnWifi)
                .putBoolean("schedule_enabled", plan.scheduleEnabled)
                .putInt("schedule_start", plan.scheduleStart)
                .putInt("schedule_end", plan.scheduleEnd)
                .putBoolean("schedule_realtime", plan.scheduleRealtime)
                .putBoolean("low_battery_enabled", plan.lowBatteryEnabled)
                .putInt("low_battery_percent", plan.lowBatteryPercent)
                .putInt("heartbeat_seconds", plan.heartbeatSeconds)
                .commit()
        )
    }
}
