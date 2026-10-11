package io.github.beacon.app.domain.model

enum class ReceiveMode(val key: String) {
    POWER_SAVING("power_saving"),
    REALTIME("realtime"),
    RULES("rules");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: POWER_SAVING
    }
}

/** Desired reception plan. The HTTP polling configuration remains the operational fallback. */
data class ReceivePlan(
    val mode: ReceiveMode = ReceiveMode.POWER_SAVING,
    val realtimeWhileCharging: Boolean = true,
    val realtimeOnWifi: Boolean = true,
    val scheduleEnabled: Boolean = false,
    val scheduleStart: Int = 9 * 60,
    val scheduleEnd: Int = 18 * 60,
    val scheduleRealtime: Boolean = true,
    val lowBatteryEnabled: Boolean = false,
    val lowBatteryPercent: Int = 20,
    val heartbeatSeconds: Int = RealtimeHeartbeat.DEFAULT_SECONDS,
) {
    fun validate() {
        RealtimeHeartbeat.validate(heartbeatSeconds)
        require(scheduleStart in 0..1439 && scheduleEnd in 0..1439)
        require(!scheduleEnabled || scheduleStart != scheduleEnd)
        require(lowBatteryPercent in 5..80)
    }
}
