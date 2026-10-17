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
        val enabled = loaded && !saving && !rulesSaving && state.runtimeConnected
        val minutes =
            listOf(1, 5, 15, 60).let {
                if (draft.freshMinutes in it) it else (it + draft.freshMinutes).sorted()
            }
        Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SettingsGroupGap)) {
            if (!loaded && !failed && state.runtimeConnected)
                SettingsHint(stringResource(R.string.settings_data_loading))
            SettingsPreferences(stringResource(R.string.notification_age_title)) {
                SettingsFieldGroup(
                    {
                        BeaconDropdownPreference(
                            stringResource(R.string.notification_fresh_window),
                            minutes.map {
                                if (it < 60) stringResource(R.string.notification_minutes, it)
                                else stringResource(R.string.notification_hours, it / 60)
                            },
                            minutes.indexOf(draft.freshMinutes),
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
                            draft.outdatedReminder.ordinal,
                            enabled = enabled,
                        ) {
                            draft = draft.copy(outdatedReminder = OutdatedReminder.entries[it])
                        }
                    },
                )
            }
            SettingsPreferences(stringResource(R.string.notification_privacy_title)) {
                BeaconPreferenceToggle(
                    stringResource(R.string.notification_lockscreen_preview),
                    "",
                    draft.lockscreenPreview,
                    enabled = enabled,
                ) {
                    draft = draft.copy(lockscreenPreview = it)
                }
            }
            if (failed) {
                BeaconText(state.status.text, color = MaterialTheme.colorScheme.error)
                BeaconActionButton(
                    stringResource(R.string.action_retry),
                    BeaconIcons.History,
                    Modifier.fillMaxWidth(),
                    !saving && !rulesSaving && state.runtimeConnected,
                ) {
                    if (loaded) onRetrySave() else attempt++
                }
            }
        }
    }
}
