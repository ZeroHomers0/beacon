package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
internal fun NotificationSettingsPanel(state: RuntimeState, actions: RuntimeActions) {
    var saved by remember { mutableStateOf(NotificationSettings()) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.runtimeConnected, attempt) {
        loaded = false
        saving = false
        failed = false
        if (state.runtimeConnected)
            actions.notificationSettings(onFailure = { failed = true }) {
                saved = it
                loaded = true
            }
    }
    fun save(value: NotificationSettings) {
        saving = true
        failed = false
        actions.notificationSettings(
            value,
            onFailure = {
                saving = false
                failed = true
            },
        ) {
            saved = it
            saving = false
        }
    }
    val enabled = loaded && !saving && state.runtimeConnected
    val minutes =
        listOf(1, 5, 15, 60).let {
            if (saved.freshMinutes in it) it else (it + saved.freshMinutes).sorted()
        }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiSection(stringResource(R.string.notification_age_title)) {
            BeaconDropdownPreference(
                stringResource(R.string.notification_fresh_window),
                minutes.map {
                    if (it < 60) stringResource(R.string.notification_minutes, it)
                    else stringResource(R.string.notification_hours, it / 60)
                },
                minutes.indexOf(saved.freshMinutes),
                enabled = enabled,
            ) {
                save(saved.copy(freshMinutes = minutes[it]))
            }
            BeaconDropdownPreference(
                stringResource(R.string.notification_outdated_behavior),
                listOf(
                    stringResource(R.string.quiet_silent),
                    stringResource(R.string.quiet_save_only),
                ),
                saved.outdatedReminder.ordinal,
                enabled = enabled,
            ) {
                save(saved.copy(outdatedReminder = OutdatedReminder.entries[it]))
            }
            SettingsHint(stringResource(R.string.notification_age_hint))
        }
        UiSection(stringResource(R.string.notification_privacy_title)) {
            BeaconPreferenceToggle(
                stringResource(R.string.notification_lockscreen_preview),
                stringResource(R.string.notification_lockscreen_hint),
                saved.lockscreenPreview,
                enabled = enabled,
            ) {
                save(saved.copy(lockscreenPreview = it))
            }
        }
        // The failure used to render inside the privacy card, which pointed at a setting that had
        // nothing to do with it; both read and write failures belong to the whole page.
        if (failed) {
            BeaconText(state.status.text, color = MaterialTheme.colorScheme.error)
            BeaconActionButton(
                stringResource(R.string.action_retry),
                BeaconIcons.History,
                Modifier.fillMaxWidth(),
                !saving && state.runtimeConnected,
            ) {
                attempt++
            }
        }
    }
}
