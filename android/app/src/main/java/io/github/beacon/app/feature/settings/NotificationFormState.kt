package io.github.beacon.app.feature.settings

import androidx.compose.runtime.*
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.NotificationSettings
import io.github.beacon.app.ipc.RuntimeActions

class NotificationFormState {
    var saved by mutableStateOf(NotificationSettings())
    var draft by mutableStateOf(saved)
    var loaded by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var loadGeneration = 0

    fun invalidate() {
        loadGeneration++
        loaded = false
        loading = false
        failed = false
        rulesFailure = null
    }

    var saving by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var rulesSaving by mutableStateOf(false)
    var rulesFailure by mutableStateOf<Int?>(null)
    var attempt by mutableIntStateOf(0)
    val changed: Boolean
        get() = loaded && draft != saved

    fun reset(value: NotificationSettings) {
        saved = value
        draft = value
    }

    fun saveRules(actions: RuntimeActions, quiet: QuietHoursFormState) {
        if (rulesSaving || saving || quiet.saving || !loaded || !quiet.loaded || !quiet.valid)
            return
        val notificationSnapshot = draft
        val quietSnapshot = quiet.pending()
        val notificationChanged = changed
        val quietChanged = quietSnapshot != quiet.saved
        rulesSaving = true
        rulesFailure = null
        failed = false
        quiet.failed = false
        fun saveQuiet() {
            if (!quietChanged) {
                quiet.reset(quietSnapshot)
                rulesSaving = false
                return
            }
            quiet.saving = true
            actions.quietHours(
                quietSnapshot,
                onFailure = {
                    quiet.saving = false
                    quiet.failed = true
                    rulesSaving = false
                    rulesFailure =
                        if (notificationChanged) R.string.settings_rules_partial_failure
                        else R.string.settings_quiet_save_failure
                },
            ) {
                quiet.reset(it)
                quiet.saving = false
                rulesSaving = false
            }
        }
        if (!notificationChanged) {
            saveQuiet()
            return
        }
        saving = true
        actions.notificationSettings(
            notificationSnapshot,
            onFailure = {
                saving = false
                failed = true
                rulesSaving = false
                rulesFailure = R.string.settings_notification_save_failure
            },
        ) {
            reset(it)
            saving = false
            saveQuiet()
        }
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
