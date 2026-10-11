package io.github.beacon.app.feature.settings

import androidx.compose.runtime.*
import io.github.beacon.app.domain.model.PollingConfig
import io.github.beacon.app.domain.model.ReceivePlan

/**
 * The reception page keeps one unsaved draft: the heartbeat, the battery-saver switch, the
 * automatic interval and the rule conditions all live here until the page's single save button
 * submits them.
 *
 * The mode selector is deliberately not part of the draft. It already carries its own prompt for
 * unsaved changes, so switching modes writes through immediately instead of silently rewriting
 * the plan the user is still editing.
 */
class ReceptionFormState {
    var plan by mutableStateOf(ReceivePlan())
    var scheduleStartText by mutableStateOf(DEFAULT_START)
    var scheduleEndText by mutableStateOf(DEFAULT_END)
    var interval by mutableIntStateOf(15)
    var unmetered by mutableStateOf(false)
    var charging by mutableStateOf(false)
    var enabled by mutableStateOf(false)
    var edited by mutableStateOf(false)

    /** The typed schedule times, or null while the text does not parse yet. */
    val scheduleStart: Int?
        get() = parseMinute(scheduleStartText)

    val scheduleEnd: Int?
        get() = parseMinute(scheduleEndText)

    val scheduleValid: Boolean
        get() =
            !plan.scheduleEnabled ||
                (scheduleStart != null && scheduleEnd != null && scheduleStart != scheduleEnd)

    /** The plan as shown on screen, with the typed schedule times folded back in. */
    fun pendingPlan(): ReceivePlan =
        plan.copy(
            scheduleStart = scheduleStart ?: plan.scheduleStart,
            scheduleEnd = scheduleEnd ?: plan.scheduleEnd,
        )

    /** Restores the polling half of the draft; the plan half keeps whatever was already loaded. */
    fun reset(config: PollingConfig) {
        interval = config.intervalMinutes
        unmetered = config.unmetered
        charging = config.charging
        enabled = config.enabled
        edited = false
    }

    fun reset(config: PollingConfig, saved: ReceivePlan) {
        reset(config)
        plan = saved
        scheduleStartText = formatMinute(saved.scheduleStart)
        scheduleEndText = formatMinute(saved.scheduleEnd)
    }

    fun differsFrom(config: PollingConfig): Boolean =
        interval != config.intervalMinutes ||
            unmetered != config.unmetered ||
            charging != config.charging ||
            enabled != config.enabled

    /**
     * True when anything on the page differs from what the runtime has saved. Both halves are
     * compared because one save button submits them together.
     */
    fun differsFrom(config: PollingConfig, saved: ReceivePlan): Boolean =
        differsFrom(config) || pendingPlan() != saved

    fun applyTo(config: PollingConfig): PollingConfig =
        config.copy(
            intervalMinutes = interval,
            unmetered = unmetered,
            charging = charging,
            enabled = enabled,
        )

    private companion object {
        const val DEFAULT_START = "09:00"
        const val DEFAULT_END = "18:00"
    }
}
