package io.github.beacon.app.feature.settings

import io.github.beacon.app.ui.components.BeaconUiMetrics

import androidx.compose.foundation.layout.*
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
    SettingsGroup(stringResource(R.string.settings_danger_section_title)) {
        SettingsCommandRow(stringResource(R.string.device_revoke), icon = BeaconIcons.Close,
            enabled = !state.messageBusy && state.runtimeConnected && state.configuredEndpoint.isNotBlank(),
            danger = true, onClick = { revoke = true })
        SettingsCommandRow(stringResource(R.string.connection_clear), icon = BeaconIcons.Delete,
            enabled = !state.messageBusy && state.runtimeConnected && state.configuredEndpoint.isNotBlank(),
            danger = true, onClick = { confirm = true })
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
,
                verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12),
            ) {
                SettingsHint(stringResource(R.string.device_revoke_desc))
                SettingsOverviewField(stringResource(R.string.settings_validation_title),
                    if (attempted) state.status.text else stringResource(R.string.settings_form_ready))
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
