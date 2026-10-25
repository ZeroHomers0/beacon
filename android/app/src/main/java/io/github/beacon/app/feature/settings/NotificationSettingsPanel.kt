package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
internal fun NotificationSettingsPanel(
    state: RuntimeState,
    actions: RuntimeActions,
    form: NotificationFormState,
    onRetrySave: () -> Unit,
) {
    with(form) {
        val enabled = pageState.hasContent && !saving && !rulesSaving && state.runtimeConnected
        val minutes =
            listOf(1, 5, 15, 60).let {
                if (draft.freshMinutes in it) it else (it + draft.freshMinutes).sorted()
            }
        Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SettingsGroupGap)) {
            // The title used to be swapped for a loading label, so the page appeared to rename
            // itself while it loaded. The section keeps its name and the body reports the state.
            SettingsPreferences(stringResource(R.string.notification_age_title)) {
                run {
                    SettingsFieldGroup(
                        {
                            BeaconDropdownPreference(
                                stringResource(R.string.notification_fresh_window),
                                minutes.map {
                                    if (it < 60) stringResource(R.string.notification_minutes, it)
                                    else stringResource(R.string.notification_hours, it / 60)
                                },
                                if (loaded) minutes.indexOf(draft.freshMinutes) else -1,
                                enabled = enabled,
                            ) {
                                draft = draft.copy(freshMinutes = minutes[it])
                            }
                        },
                        {
                            BeaconDropdownPreference(
                                stringResource(R.string.notification_outdated_behavior),
                                listOf(
                                    stringResource(R.string.quiet_silent),
                                    stringResource(R.string.quiet_save_only),
                                ),
                                if (loaded) draft.outdatedReminder.ordinal else -1,
                                enabled = enabled,
                            ) {
                                draft = draft.copy(outdatedReminder = OutdatedReminder.entries[it])
                            }
                        },
                    )
                }
            }
            SettingsGroup(stringResource(R.string.mute_group)) {
                TemporaryMuteAction(MuteScope.GLOBAL,enabled=state.runtimeConnected&&!saving&&!rulesSaving)
            }
            if (failed && rulesFailure == null) {
                SettingsRetryFeedback(
                    stringResource(R.string.settings_data_failed), failed = true,
                    onRetry =
                        if (!saving && !rulesSaving && state.runtimeConnected) {
                            { if (loaded) onRetrySave() else attempt++ }
                        } else null,
                )
            }
        }
    }
}
