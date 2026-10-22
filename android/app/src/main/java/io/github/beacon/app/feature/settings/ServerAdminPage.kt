@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.beacon.app.feature.settings

import io.github.beacon.app.ui.components.BeaconUiMetrics

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    screen: SettingsScreen,
    onNavigate: (SettingsScreen) -> Unit,
) {
    val activity = checkNotNull(LocalActivity.current as? androidx.activity.ComponentActivity)
    val model = remember(activity) { ViewModelProvider(activity)[ServerAdminViewModel::class.java] }
    LaunchedEffect(model) {
        model.restoreSession(activity)
        model.loadOnEntry()
    }
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    var endpoint by rememberSaveable { mutableStateOf(defaultEndpoint) }
    // Never make administrator input saveable: Android saved instance state is not a secret store.
    var token by remember { mutableStateOf("") }
    val tab =
        when (screen) {
            SettingsScreen.SERVER_DEVICES -> 0
            SettingsScreen.SERVER_APPLICATIONS -> 1
            SettingsScreen.SERVER_TEST -> 2
            else -> -1
        }
    val creating by model.creating.collectAsStateWithLifecycle()
    LaunchedEffect(screen) { model.dismissCreation() }
    var loginHelp by remember { mutableStateOf(false) }
    if (loginHelp)
        BeaconAlertDialog(
            onDismissRequest = { loginHelp = false },
            title = { BeaconText(stringResource(R.string.admin_token)) },
            text = { SettingsHint(stringResource(R.string.settings_admin_token_help)) },
            confirmButton = {
                BeaconButton(onClick = { loginHelp = false }) {
                    BeaconText(stringResource(R.string.action_close))
                }
            },
        )
    var bindingName by rememberSaveable { mutableStateOf("") }
    var appName by rememberSaveable { mutableStateOf("") }
    var deviceId by rememberSaveable { mutableStateOf(state.testDeviceId) }
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
    var logoutConfirm by remember { mutableStateOf(false) }
    var copiedValue by remember { mutableStateOf<String?>(null) }
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
    val allDevices =
        state.devices.sortedWith(
            compareBy<AdminDevice>(
                { it.revoked },
                { it.name.lowercase(java.util.Locale.ROOT) },
                { it.id },
            )
        )
    val allApplications =
        state.applications.sortedWith(
            compareBy<AdminApplication>(
                { it.revoked },
                { it.name.lowercase(java.util.Locale.ROOT) },
                { it.id },
            )
        )
    val active = allDevices.filterNot { it.revoked }
    var deviceQuery by rememberSaveable { mutableStateOf("") }
    var applicationQuery by rememberSaveable { mutableStateOf("") }
    var deviceStatus by rememberSaveable { mutableIntStateOf(0) }
    var applicationStatus by rememberSaveable { mutableIntStateOf(0) }
    var applicationDevice by rememberSaveable { mutableStateOf("") }
    val devices = allDevices.filter { device ->
        adminListMatches(deviceQuery, deviceStatus, device.revoked, device.name, device.id)
    }
    val applications = allApplications.filter { app ->
        val owner = allDevices.find { it.id == app.deviceId }?.name.orEmpty()
        adminListMatches(applicationQuery, applicationStatus, app.revoked, app.name, app.id, owner, app.deviceId) &&
            (applicationDevice.isBlank() || app.deviceId == applicationDevice)
    }
    LaunchedEffect(state.devices) {
        if (allDevices.none { it.id == applicationDevice }) applicationDevice = ""
    }
    LaunchedEffect(state.loggedIn, state.devices) {
        if (active.none { it.id == deviceId }) deviceId = active.firstOrNull()?.id.orEmpty()
        if (!state.loggedIn) {
            model.dismissCreation()
            pendingDevice = null
            deletingDevice = null
            clearStage = 0
            pendingApplication = null
            deletingApplication = null
            confirmTest = false
        }
    }
    LaunchedEffect(copiedValue) {
        if (copiedValue != null) { kotlinx.coroutines.delay(1500); copiedValue = null }
    }
    LaunchedEffect(state.secret) {
        copiedValue = null
        if (state.secret != null) model.dismissCreation()
    }
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
        copiedValue = if (accepted) value else null
        return accepted
    }
    BeaconSettingsList(
        state = scroll,
        modifier = modifier,
        contentPadding = padding,
        listTag = "server-admin-list",
    ) {
        // A failed refresh explains the numbers in the overview card, so there it sits inside that
        // card instead of becoming a block above it that pushes the whole page down. The other tabs
        // have no overview to explain, so they show the failure as the first row of their own list.
        if (tab != -1)
            state.refreshFailure?.let { failure ->
                item {
                    SettingsNotice(
                        stringResource(failure),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { model.refresh() },
                    )
                }
            }
        if (state.restoring) {
            item {
                SettingsSection(stringResource(R.string.admin_title)) {
                    SettingsHint(stringResource(R.string.admin_loading))
                }
            }
        } else if (state.restoreFailure != null) {
            item {
                SettingsSection(stringResource(R.string.admin_login_title)) {
                    SettingsHint(stringResource(state.restoreFailure!!))
                    BeaconButton(
                        onClick = {
                            if (state.restoreFailure == R.string.admin_credentials_clear_failed) model.logout()
                            else model.restoreSession(activity)
                        },
                        modifier = Modifier.align(androidx.compose.ui.Alignment.End),
                    ) { BeaconText(stringResource(R.string.action_retry)) }
                    SettingsCommandRow(stringResource(R.string.admin_clear_and_login),
                        icon = BeaconIcons.Close, danger = true, onClick = { model.logout() })
                }
            }
        } else if (!state.loggedIn) {
            item {
                SettingsSection(stringResource(R.string.admin_login_title)) {
                    SettingsHint(stringResource(R.string.admin_login_hint))
                    SettingsActions {
                        SettingsTextAction(stringResource(R.string.settings_admin_token_location), BeaconIcons.Info) { loginHelp = true }
                    }
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
                    SettingsPrimaryAction(
                        label = stringResource(
                            if (state.busy) R.string.admin_loading else R.string.admin_login
                        ),
                        enabled = !state.busy && endpoint.isNotBlank() && token.isNotBlank(),
                        disabledReason =
                            when {
                                state.busy -> null
                                endpoint.isBlank() ->
                                    stringResource(
                                        R.string.form_missing_field_hint,
                                        stringResource(R.string.admin_endpoint),
                                    )
                                token.isBlank() ->
                                    stringResource(
                                        R.string.form_missing_field_hint,
                                        stringResource(R.string.admin_token),
                                    )
                                else -> null
                            },
                        onClick = {
                            model.login(endpoint.trim(), token)
                            token = ""
                        },
                    )
                }
            }
        } else if (tab == -1) {
            item {
                SettingsSection(stringResource(R.string.admin_overview), action = {
                    SettingsTextAction(stringResource(R.string.admin_logout), BeaconIcons.Close, enabled = !state.busy) { logoutConfirm = true }
                }) {
                    state.refreshFailure?.let { failure ->
                        SettingsNotice(
                            stringResource(failure),
                            modifier = Modifier.padding(bottom = BeaconUiMetrics.SectionGap),
                            actionLabel = stringResource(R.string.action_retry),
                            onAction = { model.refresh() },
                        )
                    }
                    SettingsFieldGroup(
                        {
                            SettingsStatusSummary(
                                stringResource(R.string.admin_endpoint),
                                state.endpoint,
                                icon = BeaconIcons.Server,
                                interactive = false,
                                singleLineDetails = true,
                                selectableDetails = true,
                            )
                        },
                        {
                            DiagnosticRow(stringResource(R.string.admin_version), state.version)
                        },
                        {
                            DiagnosticRow(
                                stringResource(R.string.admin_compatibility),
                                stringResource(
                                    if (state.protocol == 1) R.string.admin_compatible_short
                                    else R.string.admin_protocol_unknown_short
                                ),
                            )
                        },
                    )
                }
            }
            // Counts live in their own section: the overview card answers "which server",
            // this one answers "how much traffic", and the two never compete for the eye.
            item {
                SettingsSection(stringResource(R.string.admin_stats_heading)) {
                    SettingsMetricGrid(
                        listOf(
                            stringResource(R.string.admin_devices) to
                                formatCount(state.devices.count { !it.revoked }),
                            stringResource(R.string.admin_messages) to
                                formatCount(state.messages),
                            stringResource(R.string.admin_pending) to formatCount(state.pending),
                        ),
                        columns = 3,
                    )
                    if (state.protocol != 1)
                        SettingsHint(stringResource(R.string.admin_protocol_unknown))
                }
            }
            item {
                SettingsGroup(stringResource(R.string.admin_manage_section)) {
                    SettingsActionRow(
                        stringResource(R.string.admin_devices_title),
                        stringResource(R.string.admin_devices_description),
                        BeaconIcons.Device,
                        !state.busy,
                    ) {
                        onNavigate(SettingsScreen.SERVER_DEVICES)
                    }
                    SettingsActionRow(
                        stringResource(R.string.admin_applications_title),
                        stringResource(R.string.admin_applications_description),
                        BeaconIcons.Applications,
                        !state.busy,
                    ) {
                        onNavigate(SettingsScreen.SERVER_APPLICATIONS)
                    }
                    SettingsActionRow(
                        stringResource(R.string.admin_test_title_page),
                        stringResource(R.string.admin_test_description),
                        BeaconIcons.Messages,
                        !state.busy,
                    ) {
                        onNavigate(SettingsScreen.SERVER_TEST)
                    }
                }
            }
        }
        if (state.loggedIn)
            when (tab) {
                0 -> {
                    if (allDevices.size >= 6 || deviceQuery.isNotBlank() || deviceStatus != 0) item {
                        AdminListFilters(deviceQuery, { deviceQuery = it }, deviceStatus, { deviceStatus = it },
                            stringResource(R.string.settings_search_devices))
                    }
                    if (defaultEndpoint.isBlank() || bindingSubmitted)
                        item {
                            SettingsSection(stringResource(R.string.admin_this_phone)) {
                                if (defaultEndpoint.isBlank()) {
                                    SettingsPrimaryButton(
                                        stringResource(R.string.admin_use_binding),
                                        Modifier.align(androidx.compose.ui.Alignment.End),
                                        canBindCurrent && !state.busy,
                                    ) {
                                        model.bindThisPhone(
                                            android.os.Build.MODEL.take(32),
                                            ::bindCurrent,
                                        )
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
                        if (!state.listState(allDevices.isEmpty()).hasContent) {
                            if (state.busy) PageStateLoading()
                            else
                                PageStateFailure(
                                    stringResource(R.string.admin_overview_unavailable),
                                    onRetry = { model.refresh() },
                                )
                        } else if (devices.isEmpty())
                            BeaconEmptyState(
                                BeaconIcons.Server,
                                stringResource(R.string.admin_devices_title),
                                stringResource(if (allDevices.isEmpty()) R.string.admin_no_devices else R.string.settings_no_filter_results),
                            )
                        else
                            SettingsHint(
                                stringResource(
                                    R.string.admin_list_summary,
                                    devices.size,
                                    devices.count { it.revoked },
                                )
                            )
                    }
                    items(devices, key = { "device-${it.id}" }) { device ->
                        AdminListCard(
                            device.name.ifBlank { stringResource(R.string.admin_unnamed_device) },
                            device.id,
                            stringResource(
                                if (device.revoked) R.string.admin_revoked
                                else R.string.admin_active
                            ),
                            !state.busy,
                            buildList {
                                add(
                                    R.string.ui_v2_copy_device_id to
                                        {
                                            if (copy(device.id, R.string.admin_device_id))
                                                onFeedback(
                                                    resources.getString(
                                                        R.string.settings_copied_hint
                                                    )
                                                )
                                        }
                                )
                                if (device.revoked)
                                    add(R.string.admin_delete_device to { deletingDevice = device })
                                else {
                                    add(
                                        R.string.admin_test_title_page to
                                            {
                                                deviceId = device.id
                                                model.selectTestDevice(device.id)
                                                onNavigate(SettingsScreen.SERVER_TEST)
                                            }
                                    )
                                    add(R.string.admin_revoke_device to { pendingDevice = device })
                                }
                            },
                            modifier = Modifier.animateItem(),
                            revoked = device.revoked,
                        )
                    }
                }
                1 -> {
                    if (allApplications.size >= 6 || applicationQuery.isNotBlank() || applicationStatus != 0 || applicationDevice.isNotBlank()) item {
                        AdminListFilters(applicationQuery, { applicationQuery = it }, applicationStatus, { applicationStatus = it },
                            stringResource(R.string.settings_search_apps), allDevices, applicationDevice, { applicationDevice = it })
                    }
                    item {
                        if (!state.listState(allApplications.isEmpty()).hasContent) {
                            if (state.busy) PageStateLoading()
                            else
                                PageStateFailure(
                                    stringResource(R.string.admin_overview_unavailable),
                                    onRetry = { model.refresh() },
                                )
                        } else if (applications.isEmpty())
                            BeaconEmptyState(
                                BeaconIcons.CredentialUpdate,
                                stringResource(R.string.admin_applications_title),
                                stringResource(if (allApplications.isEmpty()) R.string.admin_no_applications else R.string.settings_no_filter_results),
                            )
                        else
                            SettingsHint(
                                stringResource(
                                    R.string.admin_list_summary,
                                    applications.size,
                                    applications.count { it.revoked },
                                )
                            )
                    }
                    items(applications, key = { "application-${it.id}" }) { app ->
                        AdminListCard(
                            app.name.ifBlank { stringResource(R.string.admin_unnamed_application) },
                            stringResource(
                                R.string.admin_application_device,
                                state.devices
                                    .find { it.id == app.deviceId }
                                    ?.name
                                    ?.takeIf { it.isNotBlank() } ?: app.deviceId,
                            ),
                            stringResource(
                                    if (app.revoked) R.string.admin_revoked
                                    else R.string.admin_active
                                ),
                            !state.busy,
                            listOf(
                                if (app.revoked)
                                    R.string.admin_delete_application to
                                        {
                                            deletingApplication = app
                                        }
                                else
                                    R.string.admin_revoke_application to
                                        {
                                            pendingApplication = app
                                        }
                            ),
                            modifier = Modifier.animateItem(),
                            revoked = app.revoked, priority = app.priority,
                        )
                    }
                }
                2 ->
                    item {
                        SettingsSection(stringResource(R.string.admin_test_title_page)) {
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
                                    stringResource(priorityShortLabel(value))
                                },
                                PRIORITY_TIERS.indexOf(testPriority).coerceAtLeast(0),
                                enabled = !state.busy,
                                stacked = true,
                            ) {
                                testPriority = PRIORITY_TIERS[it]
                            }

                            SettingsActions {
                            SettingsPrimaryButton(
                                stringResource(R.string.admin_send_test),
                                Modifier,
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
        if (state.loggedIn && tab == -1) item {
            SettingsGroup(stringResource(R.string.settings_danger_section_title)) {
                SettingsCommandRow(stringResource(R.string.admin_clear_messages),
                    icon = BeaconIcons.Delete,
                    enabled = !state.busy, danger = true, onClick = { clearStage = 1 })
            }
        }
    }

    if (logoutConfirm)
        BeaconConfirmDialog(
            title = stringResource(R.string.admin_logout),
            description = stringResource(R.string.admin_logout_hint),
            danger = false,
            onDismiss = { logoutConfirm = false },
            onConfirm = {
                logoutConfirm = false
                model.logout()
                token = ""
            },
        )
    if (creating && state.loggedIn && tab in 0..1 && state.secret == null) {
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
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap),
                ) {
                    if (tab == 0) {

                        AdminField(
                            bindingName,
                            { bindingName = it.take(40) },
                            R.string.admin_device_name,
                            !state.busy,
                        )
                        SettingsHint(stringResource(R.string.admin_binding_hint))
                    } else {

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
                                stringResource(priorityShortLabel(value))
                            },
                            PRIORITY_TIERS.indexOf(priority).coerceAtLeast(0),
                            enabled = !state.busy,
                            stacked = true,
                        ) {
                            priority = PRIORITY_TIERS[it]
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
                    BeaconText(
                        stringResource(
                            if (tab == 0) R.string.admin_create_binding
                            else R.string.admin_create_application
                        )
                    )
                }
            },
            dismissButton = {
                BeaconCancelButton(enabled = !state.busy, onClick = model::dismissCreation)
            },
        )
    }
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
                        if (secret.expiresAt > 0 && defaultEndpoint.isBlank())
                            SettingsPrimaryButton(stringResource(R.string.admin_use_binding),
                                enabled = canBindCurrent && !state.busy, onClick = {
                                    bindCurrent(state.endpoint, secret.value, bindingName)
                                    model.dismissSecret()
                                })
                        if (secret.example.isNotEmpty())
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
    if (clearStage == 1)
        BeaconConfirmDialog(
            title = stringResource(R.string.admin_clear_messages_confirm),
            description = stringResource(R.string.admin_clear_messages_warning),
            danger = true,
            confirmText = stringResource(R.string.settings_cleanup_all_continue),
            onDismiss = { clearStage = 0 },
            onConfirm = { clearStage = 2 },
        )
    if (clearStage == 2)
        AdminActionConfirm(
            title = stringResource(R.string.admin_clear_messages_final),
            description = stringResource(R.string.admin_clear_messages_final_warning),
            state = state,
            onDismiss = {
                clearStage = 0
                model.dismissAction()
            },
            onConfirm = { model.clearMessages() },
        )
    deletingDevice?.let { device ->
        AdminActionConfirm(
            title = stringResource(R.string.admin_delete_device),
            description = stringResource(R.string.admin_delete_device_hint, device.name),
            state = state,
            onDismiss = {
                deletingDevice = null
                model.dismissAction()
            },
            onConfirm = { model.deleteDevice(device.id) },
        )
    }
    pendingDevice?.let { device ->
        AdminActionConfirm(
            title = stringResource(R.string.admin_revoke_device),
            description = stringResource(R.string.admin_revoke_device_hint, device.name),
            state = state,
            onDismiss = {
                pendingDevice = null
                model.dismissAction()
            },
            onConfirm = { model.revokeDevice(device.id) },
        )
    }
    pendingApplication?.let { app ->
        AdminActionConfirm(
            title = stringResource(R.string.admin_revoke_application),
            description = stringResource(R.string.admin_revoke_application_hint, app.name),
            state = state,
            onDismiss = {
                pendingApplication = null
                model.dismissAction()
            },
            onConfirm = { model.revokeApplication(app.id) },
        )
    }
    deletingApplication?.let { app ->
        AdminActionConfirm(
            title = stringResource(R.string.admin_delete_application),
            description = stringResource(R.string.admin_delete_application_hint, app.name),
            state = state,
            onDismiss = {
                deletingApplication = null
                model.dismissAction()
            },
            onConfirm = { model.deleteApplication(app.id) },
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

/**
 * A destructive confirmation that stays open while its request runs and reports a failure in place.
 * Closing first and raising a snackbar left the reader unsure whether the change had happened, so
 * the dialog keeps both actions busy until the server answers and only then dismisses itself.
 */
@Composable
private fun AdminActionConfirm(
    title: String,
    description: String,
    state: AdminUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var submitted by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.awaiting, state.actionFailure) {
        if (submitted && state.awaiting == null) {
            submitted = false
            failure = state.actionFailure
            if (failure == null) onDismiss()
        }
    }
    BeaconConfirmDialog(
        title = title,
        description = description,
        danger = true,
        enabled = state.awaiting == null,
        dismissEnabled = state.awaiting == null,
        errorText = failure?.let { stringResource(it) },
        onDismiss = { if (state.awaiting == null) onDismiss() },
        onConfirm = {
            failure = null
            submitted = true
            onConfirm()
        },
    )
}

@Composable
private fun AdminListCard(
    title: String,
    subtitle: String,
    status: String,
    enabled: Boolean,
    actions: List<Pair<Int, () -> Unit>>,
    modifier: Modifier = Modifier,
    revoked: Boolean = false,
    priority: Int? = null,
) {
    var showingDetails by remember { mutableStateOf(false) }
    if (showingDetails) BeaconAlertDialog(onDismissRequest = { showingDetails = false },
        title = { BeaconText(title) }, text = {
            androidx.compose.foundation.text.selection.SelectionContainer { BeaconText(subtitle) }
        }, confirmButton = { BeaconTextButton(onClick = { showingDetails = false }) { BeaconText(stringResource(R.string.action_close)) } })
    var menuOpen by remember { mutableStateOf(false) }
    BeaconListCard(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap4)) {
                BeaconText(
                    title,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                BeaconText(
                    if (subtitle.length > 40) subtitle.take(36) + "…" else subtitle,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                ActionFlow {
                    val tone = if (revoked) androidx.compose.material3.MaterialTheme.colorScheme.error
                        else io.github.beacon.app.ui.theme.beaconSuccessColor()
                    androidx.compose.material3.Surface(color = tone.copy(alpha = .12f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(BeaconUiMetrics.BadgeRadius),
                        modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) {
                        BeaconText(status, Modifier.padding(horizontal = BeaconUiMetrics.Gap6, vertical = BeaconUiMetrics.BadgeVerticalInset),
                            color = tone, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    }
                    if (priority != null) BeaconText(stringResource(priorityShortLabel(priority)),
                        modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                }
            }
            Box {
                SettingsToolAction(stringResource(R.string.admin_more_actions), BeaconIcons.More,
                    enabled = enabled, onClick = { menuOpen = true })
                BeaconDropdownMenu(menuOpen && enabled, { menuOpen = false }) {
                    BeaconDropdownMenuItem({ BeaconText(stringResource(R.string.settings_view_details)) }) {
                        menuOpen = false; showingDetails = true
                    }
                    actions.forEach { (label, action) ->
                        BeaconDropdownMenuItem({ BeaconText(stringResource(label), color =
                            if (label in setOf(R.string.admin_delete_device, R.string.admin_revoke_device,
                                R.string.admin_delete_application, R.string.admin_revoke_application))
                                androidx.compose.material3.MaterialTheme.colorScheme.error
                            else androidx.compose.material3.MaterialTheme.colorScheme.onSurface) }) {
                            menuOpen = false
                            action()
                        }
                    }
                }
            }
        }
    }
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
