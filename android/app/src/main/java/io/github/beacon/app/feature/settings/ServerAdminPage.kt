@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.beacon.app.feature.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.beacon.app.R
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
internal fun ServerAdminPage(
    defaultEndpoint: String,
    scroll: LazyListState,
    modifier: Modifier,
    padding: PaddingValues,
    onUseBinding: (String, String, String) -> Unit,
    onFeedback: (String) -> Unit,
    canBindCurrent: Boolean = false,
    localBindingResult: String = "",
) {
    val activity = checkNotNull(LocalActivity.current as? androidx.activity.ComponentActivity)
    val model = remember(activity) { ViewModelProvider(activity)[ServerAdminViewModel::class.java] }
    LaunchedEffect(model) { model.restoreSession(activity) }
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    var endpoint by rememberSaveable { mutableStateOf(defaultEndpoint) }
    // Never make administrator input saveable: Android saved instance state is not a secret store.
    var token by remember { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var bindingName by rememberSaveable { mutableStateOf("") }
    var appName by rememberSaveable { mutableStateOf("") }
    var deviceId by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableIntStateOf(5) }
    var testTitle by rememberSaveable { mutableStateOf("") }
    var testBody by rememberSaveable { mutableStateOf("") }
    var testPriority by rememberSaveable { mutableIntStateOf(5) }
    var pendingDevice by remember { mutableStateOf<AdminDevice?>(null) }
    var pendingApplication by remember { mutableStateOf<AdminApplication?>(null) }
    var deletingApplication by remember { mutableStateOf<AdminApplication?>(null) }
    // Two-stage cloud purge: 1 = warning, 2 = final confirmation. Mirrors local cleanup.
    var clearStage by remember { mutableIntStateOf(0) }
    var deletingDevice by remember { mutableStateOf<AdminDevice?>(null) }
    var confirmTest by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var bindingSubmitted by rememberSaveable { mutableStateOf(false) }
    val currentBind by rememberUpdatedState(onUseBinding)
    val currentCanBind by rememberUpdatedState(canBindCurrent && defaultEndpoint.isBlank())
    fun bindCurrent(endpoint: String, code: String, name: String) {
        if (!currentCanBind) return
        bindingSubmitted = true
        currentBind(endpoint, code, name)
    }
    LaunchedEffect(defaultEndpoint) {
        if (bindingSubmitted && defaultEndpoint.isNotBlank()) model.refresh()
    }
    val active = state.devices.filterNot { it.revoked }
    LaunchedEffect(state.loggedIn, state.devices) {
        if (active.none { it.id == deviceId }) deviceId = active.firstOrNull()?.id.orEmpty()
        if (!state.loggedIn) {
            pendingDevice = null
            deletingDevice = null
            clearStage = 0
            pendingApplication = null
            deletingApplication = null
            confirmTest = false
        }
    }
    LaunchedEffect(state.secret) { copied = false }
    // Administrator outcomes share the app-wide snackbar, so they are consumed once and cleared.
    val feedbackText = state.feedback?.let { stringResource(it) }
    LaunchedEffect(state.feedback) {
        if (feedbackText != null) {
            onFeedback(feedbackText)
            model.dismissFeedback()
        }
    }
    fun copy(value: String, label: Int = R.string.admin_title): Boolean {
        val accepted =
            copyToClipboard(
                context,
                resources.getString(label),
                value,
                sensitive = true,
            )
        copied = accepted
        return accepted
    }
    BeaconSettingsList(
        state = scroll,
        modifier = modifier.testTag("server-admin-list"),
        contentPadding = padding,
    ) {
        if (!state.loggedIn) {
            item {
                UiSection(stringResource(R.string.admin_login_title)) {
                    SettingsHint(stringResource(R.string.admin_login_hint))
                    AdminField(
                        endpoint,
                        { endpoint = it },
                        R.string.admin_endpoint,
                        !state.busy,
                        keyboard = KeyboardType.Uri,
                    )
                    AdminField(
                        token,
                        { token = it.trim() },
                        R.string.admin_token,
                        !state.busy,
                        password = true,
                    )
                    BeaconActionButton(
                        stringResource(
                            if (state.busy) R.string.admin_loading else R.string.admin_login
                        ),
                        BeaconIcons.Server,
                        Modifier.fillMaxWidth(),
                        !state.busy && endpoint.isNotBlank() && token.isNotBlank(),
                    ) {
                        model.login(endpoint.trim(), token)
                        token = ""
                    }
                }
            }
        } else {
            item {
                UiSection(stringResource(R.string.admin_overview)) {
                    SettingsFieldGroup(
                        {
                            DiagnosticRow(stringResource(R.string.admin_endpoint), state.endpoint)
                        },
                        {
                            DiagnosticRow(stringResource(R.string.admin_version), state.version)
                        },
                        {
                            DiagnosticRow(
                                stringResource(R.string.admin_compatibility),
                                stringResource(if (state.protocol == 1) R.string.admin_compatible_short else R.string.admin_protocol_unknown_short),
                            )
                        },
                    )
                    SettingsMetricGrid(
                        listOf(
                            stringResource(R.string.admin_devices) to state.devices.count { !it.revoked }.toString(),
                            stringResource(R.string.admin_messages) to state.messages.toString(),
                            stringResource(R.string.admin_pending) to state.pending.toString(),
                        ), columns = 3,
                    )
                    DiagnosticRow(stringResource(R.string.admin_database), android.text.format.Formatter.formatFileSize(context, state.databaseBytes))
                    if (state.protocol != 1) SettingsHint(stringResource(R.string.admin_protocol_unknown))

                    BeaconDangerButton(
                        onClick = { clearStage = 1 },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                    ) {
                        BeaconText(stringResource(R.string.admin_clear_messages))
                    }
                    BeaconActionPair(
                        {
                            BeaconOutlinedButton(
                                onClick = {
                                    model.logout()
                                    token = ""
                                }
                            ) {
                                BeaconText(stringResource(R.string.admin_logout))
                            }
                        },
                        {
                            BeaconButton(onClick = model::refresh, enabled = !state.busy) {
                                BeaconText(
                                    stringResource(
                                        if (state.busy) R.string.admin_loading
                                        else R.string.admin_refresh
                                    )
                                )
                            }
                        },
                    )
                }
            }
            item {
                UiSection(stringResource(R.string.admin_manage_section)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                                R.string.admin_devices,
                                R.string.admin_applications,
                                R.string.admin_test,
                            )
                            .forEachIndexed { index, label ->
                                BeaconFilterChip(
                                    selected = tab == index,
                                    onClick = { tab = index },
                                    label = {
                                        Box(
                                            Modifier.fillMaxWidth(),
                                            contentAlignment = androidx.compose.ui.Alignment.Center,
                                        ) {
                                            BeaconText(stringResource(label), maxLines = 1)
                                        }
                                    },
                                    enabled = !state.busy,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                    }
                }
            }
        }
        if (state.loggedIn)
            when (tab) {
                0 -> {
                    if (defaultEndpoint.isBlank() || bindingSubmitted) item {
                        UiSection(stringResource(R.string.admin_this_phone)) {
                            if (defaultEndpoint.isBlank()) {
                                BeaconActionButton(
                                    stringResource(R.string.admin_use_binding),
                                    BeaconIcons.Guide,
                                    Modifier.fillMaxWidth(),
                                    canBindCurrent && !state.busy,
                                ) {
                                    model.bindThisPhone(android.os.Build.MODEL.take(32), ::bindCurrent)
                                }
                            }
                            if (bindingSubmitted && localBindingResult.isNotBlank())
                                OperationResultPanel(
                                    stringResource(R.string.admin_local_binding_result),
                                    localBindingResult,
                                )
                        }
                    }
                    item {
                        UiSection(stringResource(R.string.admin_create_binding)) {
                            AdminField(
                                bindingName,
                                { bindingName = it.take(40) },
                                R.string.admin_device_name,
                                !state.busy,
                            )
                            SettingsHint(stringResource(R.string.admin_binding_hint))
                            BeaconActionButton(
                                stringResource(R.string.admin_create_binding),
                                BeaconIcons.Guide,
                                Modifier.fillMaxWidth(),
                                !state.busy && bindingName.isNotBlank(),
                            ) {
                                model.createBinding(bindingName)
                            }
                        }
                    }
                    if (state.devices.isEmpty())
                        item { SettingsHint(stringResource(R.string.admin_no_devices)) }
                    items(state.devices, key = { "device-${it.id}" }) { device ->
                        UiSection(device.name) {
                            SettingsFieldGroup(
                                {
                                    DiagnosticRow(stringResource(R.string.admin_device_id), device.id)
                                },
                                {
                                    DiagnosticRow(
                                        stringResource(R.string.admin_status),
                                        stringResource(
                                            if (device.revoked) R.string.admin_revoked
                                            else R.string.admin_active
                                        ),
                                    )
                                },
                            )
                            if (device.revoked)
                                BeaconDangerButton(
                                    onClick = { deletingDevice = device },
                                    enabled = !state.busy,
                                ) {
                                    BeaconText(stringResource(R.string.admin_delete_device))
                                }
                            if (!device.revoked)
                                BeaconActionPair(
                                    {
                                        BeaconOutlinedButton(
                                            onClick = {
                                                deviceId = device.id
                                                tab = 2
                                            },
                                            enabled = !state.busy,
                                        ) {
                                            BeaconText(stringResource(R.string.admin_test))
                                        }
                                    },
                                    {
                                        BeaconDangerButton(
                                            onClick = { pendingDevice = device },
                                            enabled = !state.busy,
                                        ) {
                                            BeaconText(stringResource(R.string.admin_revoke_device))
                                        }
                                    },
                                )
                        }
                    }
                }
                1 -> {
                    item {
                        UiSection(stringResource(R.string.admin_create_application)) {
                            DevicePicker(active, deviceId, { deviceId = it }, !state.busy)
                            AdminField(
                                appName,
                                { appName = it.take(40) },
                                R.string.admin_application_name,
                                !state.busy,
                            )
                            // Only the tiers the client distinguishes are offered; the server,
                            // protocol and Bark entry still accept the full 0-10 range.
                            BeaconDropdownPreference(
                                stringResource(R.string.admin_priority),
                                PRIORITY_TIERS.map { value ->
                                    stringResource(priorityLabel(value), value)
                                },
                                PRIORITY_TIERS.indexOf(priority).coerceAtLeast(0),
                                enabled = !state.busy,
                                stacked = true,
                            ) {
                                priority = PRIORITY_TIERS[it]
                            }
                            SettingsHint(stringResource(R.string.admin_application_hint))
                            BeaconActionButton(
                                stringResource(R.string.admin_create_application),
                                BeaconIcons.CredentialUpdate,
                                Modifier.fillMaxWidth(),
                                !state.busy && deviceId.isNotBlank() && appName.isNotBlank(),
                            ) {
                                model.createApplication(deviceId, appName, priority)
                            }
                        }
                    }
                    if (state.applications.isEmpty())
                        item { SettingsHint(stringResource(R.string.admin_no_applications)) }
                    items(state.applications, key = { "application-${it.id}" }) { app ->
                        UiSection(app.name) {
                            SettingsFieldGroup(
                                {
                                    DiagnosticRow(
                                        stringResource(R.string.admin_device),
                                        state.devices.find { it.id == app.deviceId }?.name ?: app.deviceId,
                                    )
                                },
                                {
                                    DiagnosticRow(
                                        stringResource(R.string.admin_priority),
                                        stringResource(priorityLabel(app.priority), app.priority),
                                    )
                                },
                                {
                                    DiagnosticRow(
                                        stringResource(R.string.admin_status),
                                        stringResource(
                                            if (app.revoked) R.string.admin_revoked
                                            else R.string.admin_active
                                        ),
                                    )
                                },
                            )
                            if (!app.revoked)
                                BeaconDangerButton(
                                    onClick = { pendingApplication = app },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !state.busy,
                                ) {
                                    BeaconText(stringResource(R.string.admin_revoke_application))
                                }
                            // Only a revoked credential can be removed, mirroring device deletion.
                            if (app.revoked)
                                BeaconDangerButton(
                                    onClick = { deletingApplication = app },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !state.busy,
                                ) {
                                    BeaconText(stringResource(R.string.admin_delete_application))
                                }
                        }
                    }
                }
                2 ->
                    item {
                        UiSection(stringResource(R.string.admin_test)) {
                            DevicePicker(active, deviceId, { deviceId = it }, !state.busy)
                            AdminField(
                                testTitle,
                                { testTitle = it.take(200) },
                                R.string.admin_test_title,
                                !state.busy,
                            )
                            AdminField(
                                testBody,
                                { testBody = it.take(4096) },
                                R.string.admin_test_body,
                                !state.busy,
                                singleLine = false,
                            )
                            BeaconDropdownPreference(
                                stringResource(R.string.admin_priority),
                                PRIORITY_TIERS.map { value ->
                                    stringResource(priorityLabel(value), value)
                                },
                                PRIORITY_TIERS.indexOf(testPriority).coerceAtLeast(0),
                                enabled = !state.busy,
                                stacked = true,
                            ) {
                                testPriority = PRIORITY_TIERS[it]
                            }

                            BeaconActionButton(
                                stringResource(R.string.admin_send_test),
                                BeaconIcons.Messages,
                                Modifier.fillMaxWidth(),
                                !state.busy &&
                                    deviceId.isNotBlank() &&
                                    testTitle.isNotBlank() &&
                                    testBody.isNotBlank(),
                            ) {
                                confirmTest = true
                            }
                        }
                    }
            }
    }
    state.secret?.let { secret ->
        BeaconAlertDialog(
            onDismissRequest = model::dismissSecret,
            title = { BeaconText(stringResource(secret.label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        DiagnosticRow(stringResource(secret.label), secret.value)
                        if (secret.expiresAt > 0) {
                            SettingsFieldDivider()
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
                    if (copied) SettingsHint(stringResource(R.string.admin_copied))
                    if (secret.expiresAt > 0 && defaultEndpoint.isBlank())
                        BeaconOutlinedButton(
                            onClick = {
                                bindCurrent(state.endpoint, secret.value, bindingName)
                                model.dismissSecret()
                            },
                            enabled = canBindCurrent && !state.busy,
                        ) {
                            BeaconText(stringResource(R.string.admin_use_binding))
                        }
                    if (secret.example.isNotEmpty())
                        BeaconOutlinedButton(onClick = { copy(secret.example) }) {
                            BeaconText(stringResource(R.string.admin_copy_example))
                        }
                }
            },
            dismissButton = {
                BeaconOutlinedButton(onClick = { copy(secret.value) }) {
                    BeaconText(stringResource(R.string.admin_copy))
                }
            },
            confirmButton = {
                BeaconButton(onClick = model::dismissSecret) {
                    BeaconText(stringResource(R.string.action_close))
                }
            },
        )
    }
    if (clearStage > 0)
        BeaconAlertDialog(
            onDismissRequest = { clearStage = 0 },
            title = {
                BeaconText(
                    stringResource(
                        if (clearStage == 1) R.string.admin_clear_messages_confirm
                        else R.string.admin_clear_messages_final
                    )
                )
            },
            text = {
                BeaconText(
                    stringResource(
                        if (clearStage == 1) R.string.admin_clear_messages_warning
                        else R.string.admin_clear_messages_final_warning
                    )
                )
            },
            confirmButton = {
                BeaconDangerButton(
                    onClick = {
                        if (clearStage == 1) clearStage = 2
                        else {
                            clearStage = 0
                            model.clearMessages()
                        }
                    },
                    enabled = !state.busy,
                ) {
                    BeaconText(
                        stringResource(
                            if (clearStage == 1) R.string.settings_cleanup_all_continue
                            else R.string.settings_cleanup_all_apply
                        )
                    )
                }
            },
            dismissButton = {
                BeaconCancelButton(onClick = { clearStage = 0 })
            },
        )
    deletingDevice?.let { device ->
        BeaconConfirmDialog(
            title = stringResource(R.string.admin_delete_device),
            description = stringResource(R.string.admin_delete_device_hint, device.name),
            onDismiss = { deletingDevice = null },
            onConfirm = {
                deletingDevice = null
                model.deleteDevice(device.id)
            },
        )
    }
    pendingDevice?.let { device ->
        BeaconConfirmDialog(
            title = stringResource(R.string.admin_revoke_device),
            description = stringResource(R.string.admin_revoke_device_hint, device.name),
            onDismiss = { pendingDevice = null },
            onConfirm = {
                pendingDevice = null
                model.revokeDevice(device.id)
            },
        )
    }
    pendingApplication?.let { app ->
        BeaconConfirmDialog(
            title = stringResource(R.string.admin_revoke_application),
            description = stringResource(R.string.admin_revoke_application_hint, app.name),
            onDismiss = { pendingApplication = null },
            onConfirm = {
                pendingApplication = null
                model.revokeApplication(app.id)
            },
        )
    }
    deletingApplication?.let { app ->
        BeaconConfirmDialog(
            title = stringResource(R.string.admin_delete_application),
            description = stringResource(R.string.admin_delete_application_hint, app.name),
            onDismiss = { deletingApplication = null },
            onConfirm = {
                deletingApplication = null
                model.deleteApplication(app.id)
            },
        )
    }
    if (confirmTest)
        BeaconConfirmDialog(
            title = stringResource(R.string.admin_send_test),
            description =
                stringResource(
                    R.string.admin_test_confirm,
                    active.find { it.id == deviceId }?.name.orEmpty(),
                ),
            onDismiss = { confirmTest = false },
            onConfirm = {
                confirmTest = false
                model.test(deviceId, testTitle, testBody, testPriority)
            },
            danger = false,
        )
}

@Composable
private fun AdminField(
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

@Composable
private fun DevicePicker(
    devices: List<AdminDevice>,
    selected: String,
    change: (String) -> Unit,
    enabled: Boolean,
) {
    if (devices.isEmpty()) SettingsHint(stringResource(R.string.admin_no_active_devices))
    else
        BeaconDropdownPreference(
            stringResource(R.string.admin_device),
            devices.map { "${it.name} · ${it.id.take(8)}" },
            devices.indexOfFirst { it.id == selected }.coerceAtLeast(0),
            enabled = enabled,
            stacked = true,
        ) {
            change(devices[it].id)
        }
}

/**
 * The four tiers the client can actually tell apart: 0 never notifies, 1-3 use the silent channel,
 * 4-7 the normal one and 8-10 the important one. The server, the wire protocol and the Bark entry
 * still accept the whole 0-10 range, so a narrow picker never narrows the contract.
 */
private val PRIORITY_TIERS = listOf(0, 2, 5, 8)

private fun priorityLabel(value: Int): Int =
    when (value) {
        0 -> R.string.admin_priority_save
        in 1..3 -> R.string.admin_priority_silent
        in 4..7 -> R.string.admin_priority_normal
        else -> R.string.admin_priority_important
    }
