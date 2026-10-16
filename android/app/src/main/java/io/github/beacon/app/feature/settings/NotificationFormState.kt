package io.github.beacon.app.feature.settings

import androidx.compose.runtime.*
import io.github.beacon.app.domain.model.NotificationSettings
import io.github.beacon.app.ipc.RuntimeActions

class NotificationFormState {
    var saved by mutableStateOf(NotificationSettings())
    var draft by mutableStateOf(saved)
    var loaded by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var attempt by mutableIntStateOf(0)
    val changed: Boolean
        get() = loaded && draft != saved

    fun reset(value: NotificationSettings) {
        saved = value
        draft = value
    }

    fun save(actions: RuntimeActions, onSaved: () -> Unit = {}) {
        if (!loaded || saving) return
        if (!changed) {
            onSaved()
            return
        }
        saving = true
        failed = false
        actions.notificationSettings(
            draft,
            onFailure = {
                saving = false
                failed = true
            },
        ) {
            reset(it)
            saving = false
            onSaved()
        }
    }
}
