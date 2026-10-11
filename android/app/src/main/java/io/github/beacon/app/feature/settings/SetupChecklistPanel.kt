package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
internal fun SetupChecklistPanel(
    state: RuntimeState,
    notificationsAllowed: Boolean,
    actions: RuntimeActions,
    onPermissions: () -> Unit,
) {
    val configured = state.configuredEndpoint.isNotBlank()
    var expanded by rememberSaveable { mutableStateOf(!configured) }
    val progress =
        SetupProgress.evaluate(
            configured,
            state.serverReachable,
            notificationsAllowed,
            state.lastManualOutcome,
        )
    val ready = state.runtimeConnected && configured && !state.messageBusy
    UiSection(stringResource(R.string.setup_check_title)) {
        BeaconPreferenceToggle(
            stringResource(R.string.setup_show_steps),
            stringResource(R.string.setup_show_steps_note),
            expanded,
            true,
        ) {
            expanded = it
        }
        if (expanded) {
            SetupCheckRow(stringResource(R.string.setup_check_connection), progress.connection)
            BeaconActionButton(
                stringResource(R.string.server_check_action),
                BeaconIcons.Server,
                Modifier.fillMaxWidth(),
                ready && !state.serverChecking,
                actions::checkServer,
            )
            SetupCheckRow(
                stringResource(R.string.setup_check_notifications),
                progress.notifications,
            )
            BeaconActionButton(
                stringResource(R.string.setup_open_notifications),
                BeaconIcons.Messages,
                Modifier.fillMaxWidth(),
                true,
                onPermissions,
            )
            SetupCheckRow(stringResource(R.string.setup_check_sync), progress.sync)
            BeaconActionButton(
                stringResource(R.string.action_sync_now),
                BeaconIcons.Sync,
                Modifier.fillMaxWidth(),
                ready,
                { actions.transport() },
            )
            SettingsHint(stringResource(R.string.setup_check_note))
        }
    }
}

@Composable
private fun SetupCheckRow(title: String, complete: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.Icon(
            if (complete) BeaconIcons.Check else BeaconIcons.Warning,
            contentDescription =
                stringResource(if (complete) R.string.setup_checked else R.string.setup_pending),
            tint =
                if (complete) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BeaconText(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
    }
}
