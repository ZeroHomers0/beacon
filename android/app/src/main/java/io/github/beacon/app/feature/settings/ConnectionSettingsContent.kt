@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.beacon.app.ui.components.*
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
    item { ConnectionSummary(runtimeState, actions, connectionForm, busy, onFeedback) }
    item { PushExamplesPanel(actions, !busy && runtimeState.configuredEndpoint.isNotBlank(),
        runtimeState.configuredEndpoint, onFeedback) }
    item { ClearConnectionPanel(runtimeState, actions, connectionForm) }
}

@Composable
private fun ConnectionSummary(
    state: RuntimeState, actions: RuntimeActions, form: ConnectionFormState,
    busy: Boolean, onFeedback: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    var editing by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
    SettingsSection(stringResource(R.string.settings_connection_status_heading), action = {
        BeaconRefreshAction(stringResource(R.string.server_check_action),
            state.runtimeConnected && !busy && state.configuredEndpoint.isNotBlank(),
            state.serverChecking, actions::checkServer)
    }) {
        SettingsStatusSummary(
            title = if (state.serverReachable == true) stringResource(R.string.connection_status_connected)
                else connectionStatusLabel(state),
            details = if (state.serverReachable == false) state.serverCheckResult else "",
            icon = when {
                state.serverChecking -> BeaconIcons.Sync
                state.serverReachable == false -> BeaconIcons.Warning
                else -> BeaconIcons.Cloud
            },
            tint = when (state.serverReachable) {
                true -> io.github.beacon.app.ui.theme.beaconSuccessColor()
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            loading = state.serverChecking, interactive = false,
            caption = stringResource(R.string.server_last_checked) + " · " + formatMessageTime(state.serverCheckedAtMs),
        )
        if (state.configuredEndpoint.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap2)) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        BeaconText(state.configuredEndpoint, style = MaterialTheme.typography.bodyMedium)
                    }
                    BeaconText(stringResource(R.string.binding_device_name) + " · " + form.deviceName,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SettingsToolAction(stringResource(R.string.binding_copy_push_address), BeaconIcons.Copy,
                    enabled = state.runtimeConnected && !busy, completed = copied, onClick = {
                    actions.copyPushKey { address ->
                        copied = copyToClipboard(context, resources.getString(R.string.settings_clipboard_push_key),
                            address, sensitive = true)
                        if (copied) onFeedback(resources.getString(R.string.settings_copied_hint))
                    }
                })
            }
        }
        SettingsActions {
            if (state.configuredEndpoint.isBlank()) {
                SettingsPrimaryButton(stringResource(R.string.settings_configure_connection),
                    enabled = state.runtimeConnected && !busy, onClick = { editing = true })
            } else SettingsTextAction(stringResource(R.string.settings_edit_connection), BeaconIcons.Tune,
                enabled = state.runtimeConnected && !busy) { editing = true }
        }
    }

    if (editing) ConnectionEditor(state, actions, form, busy, onDismiss = { editing = false })
}

@Composable
private fun ConnectionEditor(
    state: RuntimeState, actions: RuntimeActions, form: ConnectionFormState,
    busy: Boolean, onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val initialEndpoint = remember { state.configuredEndpoint.ifBlank { form.endpoint } }
    val initialName = remember { form.deviceName }
    var endpoint by remember { mutableStateOf(initialEndpoint) }
    var name by remember { mutableStateOf(initialName) }
    // Secrets remain only in this composition, never in saved instance state.
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val formatError = stringResource(R.string.binding_format_hint)
    fun close() {
        if (busy || submitted) return
        if (endpoint != initialEndpoint || name != initialName || code.isNotBlank()) discard = true
        else onDismiss()
    }
    LaunchedEffect(busy) { if (!busy && submitted) { submitted = false; error = state.status.text } }
    BeaconAlertDialog(
        onDismissRequest = ::close,
        title = { BeaconText(stringResource(R.string.settings_connection_section_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap)) {
                BeaconTextField(endpoint, { endpoint = it; error = "" }, Modifier.fillMaxWidth(),
                    label = { BeaconText(stringResource(R.string.connection_endpoint)) },
                    labelText = stringResource(R.string.connection_endpoint), singleLine = true, enabled = !busy && !submitted,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                BeaconTextField(name, { name = it }, Modifier.fillMaxWidth(),
                    label = { BeaconText(stringResource(R.string.binding_device_name)) },
                    labelText = stringResource(R.string.binding_device_name), singleLine = true, enabled = !busy && !submitted)
                BeaconTextField(code, { code = it; error = "" }, Modifier.fillMaxWidth(),
                    label = { BeaconText(stringResource(R.string.binding_code)) },
                    labelText = stringResource(R.string.binding_code), singleLine = true, enabled = !busy && !submitted,
                    placeholder = stringResource(R.string.binding_code_placeholder),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                SettingsHint(stringResource(R.string.binding_hint))
                if (error.isNotBlank()) BeaconText(error, color = MaterialTheme.colorScheme.error)
                if (submitted || busy) SettingsHint(state.status.text)
            }
        },
        confirmButton = {
            BeaconButton(enabled = !busy && !submitted && endpoint.isNotBlank() && code.isNotBlank(), onClick = {
                val request = BindingRequest(endpoint.trim(), code.trim().lowercase(java.util.Locale.ROOT), name.trim())
                if (runCatching { request.validate(BuildConfig.DEBUG) }.isFailure) error = formatError
                else {
                    focus.clearFocus()
                    submitted = true
                    actions.bindDevice(request) {
                        resetFirstUseGuideProgress(context)
                        form.endpoint = request.endpoint
                        form.deviceName = request.deviceName
                        form.bindingCode = ""
                        form.connectionEdited = false
                        form.connectionError = ""
                        code = ""
                        onDismiss()
                    }
                }
            }) { BeaconText(stringResource(R.string.binding_action)) }
        },
        dismissButton = { BeaconCancelButton(onClick = ::close, enabled = !busy && !submitted) },
    )
    if (discard) BeaconConfirmDialog(
        title = stringResource(R.string.settings_discard_connection),
        description = stringResource(R.string.settings_discard_connection_hint), danger = false,
        onDismiss = { discard = false }, onConfirm = { code = ""; onDismiss() },
    )
}
