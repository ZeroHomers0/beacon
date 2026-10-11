package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.data.remote.RotatedCredentialsDecoder
import io.github.beacon.app.domain.model.RotatedDeviceCredentials
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
fun CredentialRotationPanel(
    state: RuntimeState,
    actions: RuntimeActions,
    form: ConnectionFormState,
) {
    var show by remember { mutableStateOf(false) }
    UiSection(stringResource(R.string.rotation_title)) {
        SettingsHint(stringResource(R.string.rotation_short_hint))
        BeaconActionButton(
            stringResource(R.string.rotation_open),
            BeaconIcons.CredentialUpdate,
            Modifier.fillMaxWidth(),
            state.runtimeConnected && !state.messageBusy && state.configuredEndpoint.isNotBlank(),
        ) {
            show = true
        }
    }
    if (show)
        CredentialRotationDialog(state, { show = false }) { payload ->
            actions.rotateCredentials(payload) {
                form.endpoint = state.configuredEndpoint
                form.bindingCode = ""
                form.connectionEdited = false
                form.connectionError = ""
                show = false
            }
        }
}

@Composable
internal fun CredentialRotationDialog(
    state: RuntimeState,
    dismiss: () -> Unit,
    submit: (String) -> Unit,
) {
    // Intentional remember, not rememberSaveable: no credentials in saved instance state.
    var payload by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    BeaconAlertDialog(
        onDismissRequest = {
            if (!state.messageBusy) {
                payload = ""
                dismiss()
            }
        },
        title = { BeaconText(stringResource(R.string.rotation_open)) },
        text = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BeaconText(stringResource(R.string.rotation_description))
                BeaconTextField(
                    payload,
                    {
                        if (
                            it.toByteArray(Charsets.UTF_8).size <=
                                RotatedDeviceCredentials.MAX_PAYLOAD_BYTES
                        ) {
                            payload = it
                            invalid = false
                        } else {
                            payload = ""
                            invalid = true
                        }
                    },
                    Modifier.fillMaxWidth(),
                    label = { BeaconText(stringResource(R.string.rotation_json_label)) },
                    labelText = stringResource(R.string.rotation_json_label),
                    enabled = !state.messageBusy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (invalid) BeaconText(stringResource(R.string.rotation_invalid))
                if (attempted) BeaconText(state.status.text)
            }
        },
        confirmButton = {
            BeaconButton(
                enabled = !state.messageBusy && payload.isNotBlank(),
                onClick = {
                    try {
                        RotatedCredentialsDecoder.decode(payload)
                        val submitted = payload
                        payload = ""
                        attempted = true
                        submit(submitted)
                    } catch (_: IllegalArgumentException) {
                        invalid = true
                    }
                },
            ) {
                BeaconText(stringResource(R.string.rotation_apply))
            }
        },
        dismissButton = {
            BeaconCancelButton(
                enabled = !state.messageBusy,
                onClick = {
                    payload = ""
                    dismiss()
                },
            )
        },
    )
}
