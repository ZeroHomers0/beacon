package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.ReceiveMode
import io.github.beacon.app.domain.model.ReceivePlan

/** Rule conditions use OR; polling constraints keep their existing unmetered semantics. */
object ReceiveModePolicy {
    fun realtime(
        plan: ReceivePlan,
        enabled: Boolean,
        online: Boolean,
        charging: Boolean,
        wifi: Boolean,
        batteryPercent: Int = 100,
        localMinute: Int = 0,
    ): Boolean =
        enabled &&
            online &&
            when (plan.mode) {
                ReceiveMode.POWER_SAVING -> false
                ReceiveMode.REALTIME -> true
                ReceiveMode.RULES ->
                    when {
                        plan.lowBatteryEnabled && batteryPercent <= plan.lowBatteryPercent -> false
                        plan.scheduleEnabled &&
                            inWindow(localMinute, plan.scheduleStart, plan.scheduleEnd) ->
                            plan.scheduleRealtime
                        else ->
                            (plan.realtimeWhileCharging && charging) ||
                                (plan.realtimeOnWifi && wifi)
                    }
            }

    fun inWindow(minute: Int, start: Int, end: Int): Boolean =
        if (start < end) minute >= start && minute < end else minute >= start || minute < end
}
