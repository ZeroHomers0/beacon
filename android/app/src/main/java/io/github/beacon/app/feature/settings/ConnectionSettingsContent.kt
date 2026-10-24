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
    item { ConnectionSummary(runtimeState, actions, connectionForm, busy) }
    item { PushExamplesPanel(actions, runtimeState.runtimeConnected && !busy && runtimeState.configuredEndpoint.isNotBlank(),
        runtimeState.configuredEndpoint, onFeedback) }
    item { ClearConnectionPanel(runtimeState, actions, connectionForm) }
}

@Composable
private fun ConnectionSummary(
    state: RuntimeState, actions: RuntimeActions, form: ConnectionFormState,
    busy: Boolean,
) {
    var editing by remember { mutableStateOf(false) }
    SettingsOverviewCard(
        title = stringResource(R.string.settings_connection_status_heading),
        status = if (state.serverReachable == true && !state.serverChecking && state.runtimeConnected)
            stringResource(R.string.overview_connected) else connectionStatusLabel(state),
        subtitle = state.configuredEndpoint.ifBlank { stringResource(R.string.settings_receive_unbound) },
        healthy = state.runtimeConnected && state.serverReachable == true && !state.serverChecking,
        error = state.serverReachable == false,
        action = {
            BeaconRefreshAction(stringResource(R.string.server_check_action),
                state.runtimeConnected && !busy && state.configuredEndpoint.isNotBlank(),
                state.serverChecking, actions::checkServer)
        },
    ) {
        SettingsOverviewField(stringResource(R.string.binding_device_name), form.deviceName)
        SettingsOverviewField(stringResource(R.string.server_last_checked), formatMessageTime(state.serverCheckedAtMs))
        SettingsOverviewField(stringResource(if (state.serverReachable == false)
            R.string.overview_check_result else R.string.settings_receive_mode_section),
            if (state.serverReachable == false) state.serverCheckResult else stringResource(receiveModeTitle(state.receivePlan.mode)),
            error = state.serverReachable == false)
        SettingsPrimaryButton(
            label = stringResource(if (state.configuredEndpoint.isBlank())
                R.string.settings_configure_connection else R.string.settings_edit_connection),
            modifier = Modifier.fillMaxWidth(),
            enabled = state.runtimeConnected && !busy,
            onClick = { editing = true },
        )
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
