package io.github.beacon.app.feature.settings

import io.github.beacon.app.ui.components.BeaconUiMetrics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
fun ClearConnectionPanel(state: RuntimeState, actions: RuntimeActions, form: ConnectionFormState) {
    var confirm by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf(false) }
    SettingsSection(stringResource(R.string.settings_danger_section_title)) {
        BeaconDangerButton(
            onClick = { revoke = true },
            modifier = Modifier.fillMaxWidth(),
            enabled =
                !state.messageBusy &&
                    state.runtimeConnected &&
                    state.configuredEndpoint.isNotBlank(),
        ) {
            BeaconText(stringResource(R.string.device_revoke))
        }

        BeaconActionButton(
            stringResource(R.string.connection_clear),
            BeaconIcons.Delete,
            Modifier.fillMaxWidth(),
            !state.messageBusy && state.runtimeConnected && state.configuredEndpoint.isNotBlank(),
        ) {
            confirm = true
        }
    }
    if (revoke)
        DeviceRevocationDialog(state, { revoke = false }) {
            actions.revokeDevice {
                form.endpoint = ""
                form.bindingCode = ""
                form.connectionEdited = false
                form.connectionError = ""
                revoke = false
            }
        }
    if (confirm)
        BeaconConfirmDialog(
            title = stringResource(R.string.connection_clear),
            description = stringResource(R.string.connection_clear_desc),
            confirmText = stringResource(R.string.connection_clear_confirm),
            enabled = !state.messageBusy,
            dismissEnabled = !state.messageBusy,
            onDismiss = { if (!state.messageBusy) confirm = false },
            onConfirm = {
                actions.clearConnection {
                    form.endpoint = ""
                    form.bindingCode = ""
                    form.connectionEdited = false
                    form.connectionError = ""
                    confirm = false
                }
            },
        )
}

@Composable
internal fun DeviceRevocationDialog(state: RuntimeState, dismiss: () -> Unit, submit: () -> Unit) {
    var attempted by remember { mutableStateOf(false) }
    BeaconAlertDialog(
        onDismissRequest = { if (!state.messageBusy) dismiss() },
        title = { BeaconText(stringResource(R.string.device_revoke)) },
        text = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12),
            ) {
                SettingsHint(stringResource(R.string.device_revoke_desc))
                if (attempted) BeaconText(state.status.text)
            }
        },
        confirmButton = {
            BeaconDangerButton(
                enabled = !state.messageBusy,
                onClick = {
                    attempted = true
                    submit()
                },
            ) {
                BeaconText(stringResource(R.string.device_revoke_confirm))
            }
        },
        dismissButton = {
            BeaconCancelButton(enabled = !state.messageBusy, onClick = dismiss)
        },
    )
}
