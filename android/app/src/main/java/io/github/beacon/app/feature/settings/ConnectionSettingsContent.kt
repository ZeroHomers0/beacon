@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.BindingRequest
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconActionButton
import io.github.beacon.app.ui.components.BeaconButton
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconTextField
import io.github.beacon.app.ui.components.SettingsGroup
import io.github.beacon.app.ui.components.SettingsPrimaryAction
import io.github.beacon.app.ui.components.SettingsSection
import io.github.beacon.app.ui.components.SettingsStatusSummary
import io.github.beacon.app.ui.components.connectionStatusLabel
import io.github.beacon.app.ui.components.copyToClipboard
import io.github.beacon.app.ui.components.formatMessageTime
import io.github.beacon.app.ui.icons.BeaconIcons

internal fun LazyListScope.connectionSettingsItems(
    resources: android.content.res.Resources,
    runtimeState: RuntimeState,
    actions: RuntimeActions,
    connectionForm: ConnectionFormState,
    connectionDirty: Boolean,
    busy: Boolean,
    context: Context,
    focus: FocusManager,
    bindingFormatHint: String,
    onFeedback: (String) -> Unit,
) {
    item {
        // SettingsSection already provides the card inset and the section gap this block used to
        // rebuild by hand.
        SettingsSection(stringResource(R.string.settings_connection_status_heading)) {
            val label =
                if (runtimeState.serverReachable == true)
                    resources.getString(R.string.connection_status_connected)
                else connectionStatusLabel(runtimeState)
            val details =
                when {
                    runtimeState.serverReachable == true ->
                        resources.getString(R.string.server_status_protocols)
                    runtimeState.serverReachable == false ->
                        runtimeState.serverCheckResult.substringAfter(
                            " · ",
                            runtimeState.serverCheckResult,
                        )
                    else -> ""
                }
            SettingsStatusSummary(
                label,
                details,
                icon =
                    when {
                        runtimeState.serverChecking -> BeaconIcons.Sync
                        runtimeState.serverReachable == false -> BeaconIcons.Warning
                        else -> BeaconIcons.Cloud
                    },
                tint =
                    if (runtimeState.serverReachable == false) MaterialTheme.colorScheme.error
                    else if (runtimeState.serverReachable == true)
                        io.github.beacon.app.ui.theme.beaconSuccessColor()
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                loading = runtimeState.serverChecking,
                interactive = false,
                // The check time belongs to the two lines above, not to the card: it shares their
                // column, so it lines up with the title instead of floating under a centred row.
                caption =
                    stringResource(R.string.server_last_checked) +
                        " · " +
                        formatMessageTime(runtimeState.serverCheckedAtMs),
            )
            io.github.beacon.app.ui.components.BeaconCheckConnectionButton(
                stringResource(
                    if (runtimeState.serverChecking) R.string.server_checking
                    else R.string.server_check_action
                ),
                runtimeState.serverChecking,
                runtimeState.runtimeConnected &&
                    runtimeState.configuredEndpoint.isNotBlank(),
                actions::checkServer,
            )
        }
    }
    item {
        SettingsSection(stringResource(R.string.settings_connection_section_title)) {
            BeaconTextField(
                connectionForm.endpoint,
                {
                    connectionForm.endpoint = it
                    connectionForm.connectionEdited = true
                    connectionForm.connectionError = ""
                },
                Modifier.fillMaxWidth(),
                label = { BeaconText(stringResource(R.string.connection_endpoint)) },
                labelText = stringResource(R.string.connection_endpoint),
                singleLine = true,
                enabled = !busy,
            )
            BeaconTextField(
                connectionForm.deviceName,
                {
                    connectionForm.deviceName = it
                    connectionForm.connectionEdited = true
                },
                Modifier.fillMaxWidth(),
                label = { BeaconText(stringResource(R.string.binding_device_name)) },
                labelText = stringResource(R.string.binding_device_name),
                singleLine = true,
                enabled = !busy,
            )
            BeaconTextField(
                connectionForm.bindingCode,
                {
                    connectionForm.bindingCode = it
                    connectionForm.connectionEdited = true
                    connectionForm.connectionError = ""
                },
                Modifier.fillMaxWidth(),
                label = { BeaconText(stringResource(R.string.binding_code)) },
                labelText = stringResource(R.string.binding_code),
                placeholder = stringResource(R.string.binding_code_placeholder),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !busy,
            )
            BeaconText(
                stringResource(R.string.binding_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            if (connectionForm.connectionError.isNotBlank())
                BeaconText(
                    connectionForm.connectionError,
                    color = MaterialTheme.colorScheme.error,
                )
            val bindEnabled =
                !busy &&
                    connectionDirty &&
                    connectionForm.endpoint.isNotBlank() &&
                    connectionForm.bindingCode.isNotBlank()
            SettingsPrimaryAction(
                label = stringResource(R.string.binding_action),
                enabled = bindEnabled,
                disabledReason =
                    when {
                        busy || bindEnabled -> null
                        connectionForm.endpoint.isBlank() ->
                            stringResource(
                                R.string.form_missing_field_hint,
                                stringResource(R.string.connection_endpoint),
                            )
                        connectionForm.bindingCode.isBlank() ->
                            stringResource(
                                R.string.form_missing_field_hint,
                                stringResource(R.string.binding_code),
                            )
                        else -> null
                    },
                onClick = {
                    val request =
                        BindingRequest(
                            connectionForm.endpoint.trim(),
                            connectionForm.bindingCode.trim().lowercase(java.util.Locale.ROOT),
                            connectionForm.deviceName.trim(),
                        )
                    try {
                        request.validate(BuildConfig.DEBUG)
                        focus.clearFocus()
                        actions.bindDevice(request) {
                            resetFirstUseGuideProgress(context)
                            connectionForm.endpoint = request.endpoint
                            connectionForm.bindingCode = ""
                            connectionForm.connectionEdited = false
                            connectionForm.connectionError = ""
                        }
                    } catch (_: Exception) {
                        connectionForm.connectionError = bindingFormatHint
                    }
                },
            )

            BeaconActionButton(
                stringResource(R.string.binding_copy_push_address),
                BeaconIcons.Copy,
                Modifier.fillMaxWidth(),
                !busy && runtimeState.configuredEndpoint.isNotBlank(),
            ) {
                actions.copyPushKey { address ->
                    val copied =
                        copyToClipboard(
                            context,
                            resources.getString(R.string.settings_clipboard_push_key),
                            address,
                            sensitive = true,
                        )
                    if (copied) onFeedback(resources.getString(R.string.settings_copied_hint))
                }
            }
        }
    }
    item {
        PushExamplesPanel(
            actions,
            !busy && runtimeState.configuredEndpoint.isNotBlank(),
            runtimeState.configuredEndpoint,
            onFeedback,
        )
    }
    item { ClearConnectionPanel(runtimeState, actions, connectionForm) }
}