package io.github.beacon.app.feature.settings

import androidx.compose.runtime.*
import io.github.beacon.app.domain.model.QuietHours
import io.github.beacon.app.ipc.RuntimeActions

/** One draft shared by the notification rules page and its persistent toolbar save action. */
class QuietHoursFormState {
    var saved by mutableStateOf(QuietHours())
    var draft by mutableStateOf(saved)
    var start by mutableStateOf("22:00")
    var end by mutableStateOf("08:00")
    var loaded by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var attempt by mutableIntStateOf(0)

    val first: Int? get() = parseMinute(start)
    val last: Int? get() = parseMinute(end)
    val valid: Boolean get() = first != null && last != null && first != last && draft.weekdays != 0
    val changed: Boolean get() = pending() != saved
    val canSave: Boolean get() = loaded && !saving && valid && changed

    fun pending(): QuietHours = draft.copy(startMinute = first ?: draft.startMinute, endMinute = last ?: draft.endMinute)

    fun reset(value: QuietHours) {
        saved = value
        draft = value
        start = formatMinute(value.startMinute)
        end = formatMinute(value.endMinute)
    }

    fun save(actions: RuntimeActions) {
        if (!canSave) return
        saving = true
        failed = false
        actions.quietHours(pending(), onFailure = { saving = false; failed = true }) {
            reset(it)
            saving = false
        }
    }
}
