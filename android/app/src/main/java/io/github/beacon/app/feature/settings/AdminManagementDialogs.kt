@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.beacon.app.feature.settings

import io.github.beacon.app.ui.components.BeaconUiMetrics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.beacon.app.R
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

/** Creation and one-time credential dialogs share the page's drafts and ViewModel. */
@Composable
internal fun AdminCreationDialog(
    state: AdminUiState, model: ServerAdminViewModel, tab: Int, active: List<AdminDevice>,
    bindingName: String, onBindingName: (String)->Unit,
    appName: String, onAppName: (String)->Unit, deviceId: String, onDevice: (String)->Unit,
    priority: Int, onPriority: (Int)->Unit,
) {
    if (state.loggedIn && tab in 0..1 && state.secret == null) {
        BeaconAlertDialog(
            onDismissRequest = { if (!state.busy) model.dismissCreation() },
            title = {
                BeaconText(
                    stringResource(
                        if (tab == 0) R.string.admin_create_binding
                        else R.string.admin_create_application
                    )
                )
            },
            text = {
                Column(
                    Modifier.fillMaxWidth()
,
                    verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap),
                ) {
                    if (tab == 0) {

                        AdminField(
                            bindingName,
                            { onBindingName(it.take(40)) },
                            R.string.admin_device_name,
                            !state.busy,
                        )
                        SettingsHint(stringResource(R.string.admin_binding_hint))
                    } else {

                        DevicePicker(active, deviceId, onDevice, !state.busy)
                        AdminField(
                            appName,
                            { onAppName(it.take(40)) },
                            R.string.admin_application_name,
                            !state.busy,
                        )
                        // Only the tiers the client distinguishes are offered; the server,
                        // protocol and Bark entry still accept the full 0-10 range.
                        BeaconDropdownPreference(
                            stringResource(R.string.admin_priority),
                            PRIORITY_TIERS.map { value ->
                                stringResource(priorityShortLabel(value))
                            },
                            PRIORITY_TIERS.indexOf(priority).coerceAtLeast(0),
                            enabled = !state.busy,
                        ) {
                            onPriority(PRIORITY_TIERS[it])
                        }
                        SettingsHint(stringResource(R.string.admin_application_hint))
                    }
                }
            },
            confirmButton = {
                BeaconButton(
                    enabled =
                        !state.busy &&
                            if (tab == 0) bindingName.isNotBlank()
                            else deviceId.isNotBlank() && appName.isNotBlank(),
                    onClick = {
                        if (tab == 0) model.createBinding(bindingName)
                        else model.createApplication(deviceId, appName, priority)
                    },
                ) {
                    BeaconText(stringResource(R.string.action_create))
                }
            },
            dismissButton = {
                BeaconCancelButton(enabled = !state.busy, onClick = model::dismissCreation)
            },
        )
    }
}

@Composable
internal fun AdminSecretDialog(
    state: AdminUiState, model: ServerAdminViewModel, copiedValue: String?, copy: (String)->Unit,
    allowBinding: Boolean, canBindCurrent: Boolean, bindingName: String,
    bindCurrent: (String,String,String)->Unit,
) {
    state.secret?.let { secret ->
        BeaconAlertDialog(
            onDismissRequest = model::dismissSecret,
            title = { BeaconText(stringResource(secret.label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12)) {
                    Column {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    DiagnosticRow(stringResource(secret.label), secret.value)
                                }
                            }
                            SettingsToolAction(stringResource(R.string.admin_copy), BeaconIcons.Copy,
                                completed = copiedValue == secret.value, onClick = { copy(secret.value) })
                        }
                        if (secret.expiresAt > 0) {
                            DiagnosticRow(
                                stringResource(R.string.admin_expires),
                                formatMessageTime(secret.expiresAt),
                            )
                        }
                    }
                    SettingsHint(
                        stringResource(
                            if (secret.example.isEmpty()) R.string.admin_binding_hint
                            else R.string.admin_secret_hint
                        )
                    )
                    SettingsActions {
                        if (secret.expiresAt > 0 && allowBinding)
                            SettingsPrimaryButton(stringResource(R.string.admin_use_binding),
                                enabled = canBindCurrent && !state.busy, onClick = {
                                    bindCurrent(state.endpoint, secret.value, bindingName)
                                    model.dismissSecret()
                                })
                    }
                    if (secret.example.isNotEmpty()) Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End) {
                        SettingsToolAction(stringResource(R.string.admin_copy_example), BeaconIcons.Copy,
                            completed = copiedValue == secret.example, onClick = { copy(secret.example) })
                    }
                }
            },
            confirmButton = {
                BeaconTextButton(onClick = model::dismissSecret) {
                    BeaconText(stringResource(R.string.settings_complete))
                }
            },
        )
    }
}

@Composable
internal fun AdminField(
    value: String,
    change: (String) -> Unit,
    label: Int,
    enabled: Boolean,
    password: Boolean = false,
    singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    val text = stringResource(label)
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    BeaconTextField(
        value,
        change,
        Modifier.fillMaxWidth(),
        label = { BeaconText(text) },
        labelText = text,
        singleLine = singleLine,
        enabled = enabled,
        visualTransformation =
            if (password) PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboard,
                autoCorrectEnabled = false,
                imeAction =
                    if (singleLine) androidx.compose.ui.text.input.ImeAction.Done
                    else androidx.compose.ui.text.input.ImeAction.Default,
            ),
        keyboardActions =
            androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
    )
}
