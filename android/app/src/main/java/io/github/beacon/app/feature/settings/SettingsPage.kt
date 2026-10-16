@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.BeaconThemeMode
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.domain.model.ReceivePlan
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconDropdownPreference
import io.github.beacon.app.ui.components.BeaconSettingsList
import io.github.beacon.app.ui.components.NotificationPermissionState
import io.github.beacon.app.ui.components.SettingsGroup
import io.github.beacon.app.ui.components.SettingsRow
import io.github.beacon.app.ui.components.SettingsScreen
import io.github.beacon.app.ui.components.receiveLabel
import io.github.beacon.app.ui.components.receiveModeIcon
import io.github.beacon.app.ui.components.receiveModeTitle
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
fun SettingsPage(
    screen: SettingsScreen,
    scroll: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
    padding: PaddingValues,
    runtimeState: RuntimeState,
    actions: RuntimeActions,
    connectionForm: ConnectionFormState,
    connectionDirty: Boolean,
    notificationPermission: NotificationPermissionState,
    notificationHint: Int?,
    uiStyle: BeaconUiStyle,
    themeMode: BeaconThemeMode,
    onNavigate: (SettingsScreen) -> Unit,
    receivePlan: ReceivePlan,
    receptionForm: ReceptionFormState,
    onShowReceiveModes: () -> Unit,
    onUiStyleChange: (BeaconUiStyle) -> Unit,
    onThemeModeChange: (BeaconThemeMode) -> Unit,
    onOperationDetails: (String) -> Unit,
    onFeedback: (String) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onNotificationSettingsUnavailable: () -> Unit,
    updateState: UpdateCheckState,
    onCheckUpdate: () -> Unit,
    notificationForm: NotificationFormState =
        androidx.compose.runtime.remember { NotificationFormState() },
    quietHoursForm: QuietHoursFormState =
        androidx.compose.runtime.remember { QuietHoursFormState() },
) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val focus = LocalFocusManager.current
    val busy = runtimeState.messageBusy
    val polling = runtimeState.pollingState
    var pendingReminderCount by
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    var reminderFailed by
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var reminderAttempt by
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(
        reminderAttempt,
        screen,
        runtimeState.runtimeConnected,
        runtimeState.messageBusy,
    ) {
        if (
            screen == SettingsScreen.NOTIFICATIONS &&
                runtimeState.runtimeConnected &&
                !runtimeState.messageBusy
        ) {
            pendingReminderCount = null
            reminderFailed = false
            actions.pendingReminders(onFailure = { reminderFailed = true }) {
                pendingReminderCount = it
            }
        } else if (!runtimeState.runtimeConnected) pendingReminderCount = null
    }
    androidx.compose.runtime.LaunchedEffect(
        screen,
        runtimeState.runtimeConnected,
        notificationForm.attempt,
    ) {
        if (
            screen in setOf(SettingsScreen.NOTIFICATION_RULES, SettingsScreen.QUIET_HOURS) &&
                runtimeState.runtimeConnected &&
                !notificationForm.loaded &&
                !notificationForm.loading
        ) {
            notificationForm.failed = false
            notificationForm.loading = true
            val generation = notificationForm.loadGeneration
            actions.notificationSettings(
                onFailure = {
                    if (generation == notificationForm.loadGeneration) {
                        notificationForm.failed = true
                        notificationForm.loading = false
                    }
                }
            ) {
                if (generation == notificationForm.loadGeneration) {
                    notificationForm.reset(it)
                    notificationForm.loaded = true
                    notificationForm.loading = false
                }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(
        runtimeState.configuredEndpoint,
        runtimeState.connectionSummaryLoaded,
    ) {
        if (runtimeState.connectionSummaryLoaded)
            syncFirstUseGuideBinding(context, runtimeState.configuredEndpoint)
    }
    // Resolved here because its only use site is a non-composable callback.
    val bindingFormatHint = stringResource(R.string.binding_format_hint)
    if (
        screen in
            setOf(
                SettingsScreen.SERVER_ADMIN,
                SettingsScreen.SERVER_DEVICES,
                SettingsScreen.SERVER_APPLICATIONS,
                SettingsScreen.SERVER_TEST,
            )
    ) {
        ServerAdminPage(
            runtimeState.configuredEndpoint,
            scroll,
            modifier,
            padding,
            screen = screen,
            onNavigate = onNavigate,
            onUseBinding = { endpoint, code, name ->
                val current = actions.state.value
                if (
                    current.configuredEndpoint.isBlank() &&
                        current.connectionSummaryLoaded &&
                        current.runtimeConnected &&
                        !current.messageBusy
                ) {
                    actions.bindDevice(
                        io.github.beacon.app.domain.model.BindingRequest(endpoint, code, name)
                    ) {
                        resetFirstUseGuideProgress(context)
                        connectionForm.endpoint = endpoint
                        connectionForm.bindingCode = ""
                        connectionForm.deviceName = name
                        connectionForm.connectionEdited = false
                        connectionForm.connectionError = ""
                    }
                }
            },
            canBindCurrent =
                runtimeState.connectionSummaryLoaded && runtimeState.runtimeConnected && !busy,
            localBindingResult = runtimeState.status.text,
            onFeedback = onFeedback,
        )
        return
    }
    BeaconSettingsList(state = scroll, modifier = modifier, contentPadding = padding) {
        if (
            !runtimeState.runtimeConnected &&
                screen in
                    setOf(
                        SettingsScreen.CONNECTION,
                        SettingsScreen.RECEPTION,
                        SettingsScreen.NOTIFICATIONS,
                        SettingsScreen.NOTIFICATION_RULES,
                        SettingsScreen.QUIET_HOURS,
                        SettingsScreen.STORAGE,
                        SettingsScreen.BACKUP,
                        SettingsScreen.GUIDE,
                    )
        ) {
            item {
                io.github.beacon.app.ui.components.SettingsSection(
                    stringResource(R.string.settings_runtime_title)
                ) {
                    io.github.beacon.app.ui.components.SettingsHint(
                        stringResource(R.string.settings_runtime_disconnected)
                    )
                    io.github.beacon.app.ui.components.BeaconOutlinedButton(
                        onClick = { actions.start(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        io.github.beacon.app.ui.components.BeaconText(
                            stringResource(R.string.action_retry)
                        )
                    }
                }
            }
        }
        if (screen in setOf(SettingsScreen.NOTIFICATION_RULES, SettingsScreen.QUIET_HOURS)) {
            if (notificationForm.rulesSaving)
                item {
                    io.github.beacon.app.ui.components.SettingsHint(
                        stringResource(R.string.settings_saving)
                    )
                }
            notificationForm.rulesFailure?.let { failure ->
                item {
                    io.github.beacon.app.ui.components.BeaconText(
                        stringResource(failure),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        when (screen) {
            SettingsScreen.HOME,
            SettingsScreen.APPEARANCE -> {
                item {
                    SettingsGroup(stringResource(R.string.settings_receive_section_title)) {
                        SettingsRow(
                            stringResource(R.string.ui_v2_server_config_title),
                            stringResource(R.string.settings_first_setup_hint),
                            BeaconIcons.Server,
                        ) {
                            onNavigate(SettingsScreen.CONNECTION)
                        }
                        io.github.beacon.app.ui.components.SettingsFieldDivider()
                        SettingsRow(
                            stringResource(R.string.settings_reception_page_title),
                            stringResource(receiveModeTitle(receivePlan.mode)) +
                                " · " +
                                receiveLabel(polling),
                            receiveModeIcon(receivePlan.mode),
                            iconTint =
                                io.github.beacon.app.ui.components.receiveModeColor(
                                    receivePlan.mode
                                ),
                            onClick = { onNavigate(SettingsScreen.RECEPTION) },
                        )
                        io.github.beacon.app.ui.components.SettingsFieldDivider()
                        SettingsRow(
                            stringResource(R.string.settings_notifications_section_title),
                            notificationHint?.let { stringResource(it) }
                                ?: when (notificationPermission) {
                                    NotificationPermissionState.ALLOWED ->
                                        stringResource(
                                            R.string.settings_notifications_allowed_label
                                        )
                                    NotificationPermissionState.DENIED ->
                                        stringResource(R.string.app_system_notification_denied_hint)
                                    NotificationPermissionState.UNKNOWN ->
                                        stringResource(R.string.diag_not_read_label)
                                },
                            BeaconIcons.Messages,
                        ) {
                            onNavigate(SettingsScreen.NOTIFICATIONS)
                        }
                        io.github.beacon.app.ui.components.SettingsFieldDivider()
                        SettingsRow(
                            stringResource(R.string.quiet_title),
                            stringResource(R.string.quiet_description),
                            BeaconIcons.QuietHours,
                        ) {
                            onNavigate(SettingsScreen.NOTIFICATION_RULES)
                        }
                        io.github.beacon.app.ui.components.SettingsFieldDivider()
                        SettingsRow(
                            stringResource(R.string.settings_background_permissions_title),
                            stringResource(R.string.settings_background_permissions_desc),
                            BeaconIcons.BackgroundTasks,
                        ) {
                            onNavigate(SettingsScreen.BACKGROUND)
                        }
                    }
                }
                item {
                    SettingsGroup(stringResource(R.string.settings_interface_section_title)) {
                        BeaconDropdownPreference(
                            stringResource(R.string.appearance_style),
                            listOf(
                                stringResource(R.string.ui_v2_style_material3),
                                stringResource(R.string.ui_v2_style_miuix),
                            ),
                            uiStyle.ordinal,
                            icon = BeaconIcons.Palette,
                            settingsRow = true,
                        ) {
                            onUiStyleChange(BeaconUiStyle.entries[it])
                        }
                        io.github.beacon.app.ui.components.SettingsFieldDivider()
                        BeaconDropdownPreference(
                            stringResource(R.string.appearance_theme),
                            listOf(
                                stringResource(R.string.settings_style_system_label),
                                stringResource(R.string.settings_style_light_label),
                                stringResource(R.string.settings_style_dark_label),
                            ),
                            themeMode.ordinal,
                            icon = BeaconIcons.ThemeMode,
                            settingsRow = true,
                        ) {
                            onThemeModeChange(BeaconThemeMode.entries[it])
                        }
                    }
                }
                item {
                    SettingsGroup(stringResource(R.string.settings_misc_section_title)) {
                        SettingsRow(
                            stringResource(R.string.admin_title),
                            "",
                            BeaconIcons.Server,
                        ) {
                            onNavigate(SettingsScreen.SERVER_ADMIN)
                        }
                        io.github.beacon.app.ui.components.SettingsFieldDivider()
                        SettingsRow(
                            stringResource(R.string.settings_storage_title),
                            "",
                            BeaconIcons.Storage,
                        ) {
                            onNavigate(SettingsScreen.STORAGE)
                        }
                        io.github.beacon.app.ui.components.SettingsFieldDivider()
                        SettingsRow(
                            stringResource(R.string.settings_backup_title),
                            "",
                            BeaconIcons.Backup,
                        ) {
                            onNavigate(SettingsScreen.BACKUP)
                        }
                        io.github.beacon.app.ui.components.SettingsFieldDivider()
                        SettingsRow(
                            title = stringResource(R.string.settings_update_title),
                            subtitle = "",
                            icon = BeaconIcons.Update,
                            iconTint = MaterialTheme.colorScheme.onSurface,
                            onClick = {
                                updateState.acknowledge()
                                when {
                                    // A check is already running; report it instead of starting
                                    // another.
                                    updateState.checking ->
                                        onFeedback(
                                            resources.getString(R.string.settings_update_checking)
                                        )
                                    updateState.release != null -> updateState.openDialog()
                                    else -> onCheckUpdate()
                                }
                            },
                            badge = updateState.hasUnreadUpdate,
                        )
                        io.github.beacon.app.ui.components.SettingsFieldDivider()
                        SettingsRow(
                            stringResource(R.string.settings_about_title),
                            "",
                            BeaconIcons.Info,
                        ) {
                            onNavigate(SettingsScreen.ABOUT)
                        }
                        io.github.beacon.app.ui.components.SettingsFieldDivider()
                        SettingsRow(
                            stringResource(R.string.settings_guide_title),
                            "",
                            BeaconIcons.Guide,
                        ) {
                            onNavigate(SettingsScreen.GUIDE)
                        }
                    }
                }
            }
            SettingsScreen.QUIET_HOURS -> {
                item {
                    QuietHoursPanel(
                        runtimeState,
                        actions,
                        quietHoursForm,
                        scheduleOnly = true,
                        editorSaving = notificationForm.rulesSaving,
                        onRetrySave = { notificationForm.saveRules(actions, quietHoursForm) },
                    )
                }
                if (notificationForm.failed)
                    item {
                        io.github.beacon.app.ui.components.SettingsSection(
                            stringResource(R.string.notification_age_title)
                        ) {
                            io.github.beacon.app.ui.components.SettingsHint(
                                stringResource(R.string.settings_data_failed)
                            )
                            io.github.beacon.app.ui.components.BeaconOutlinedButton(
                                onClick = {
                                    if (notificationForm.loaded)
                                        notificationForm.saveRules(actions, quietHoursForm)
                                    else notificationForm.attempt++
                                },
                                enabled = runtimeState.runtimeConnected,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                io.github.beacon.app.ui.components.BeaconText(
                                    stringResource(R.string.action_retry)
                                )
                            }
                        }
                    }
            }
            SettingsScreen.SERVER_ADMIN,
            SettingsScreen.SERVER_DEVICES,
            SettingsScreen.SERVER_APPLICATIONS,
            SettingsScreen.SERVER_TEST -> Unit
            SettingsScreen.GUIDE -> {
                item {
                    FirstUseGuidePanel(
                        runtimeState,
                        notificationPermission == NotificationPermissionState.ALLOWED,
                        actions,
                        onNavigate,
                    )
                }
            }
            SettingsScreen.BACKUP -> {
                item {
                    SettingsGroup(stringResource(R.string.settings_backup_settings_title)) {
                        val backupBusy =
                            busy || runtimeState.transportBusy || !runtimeState.runtimeConnected
                        SettingsBackupPanel(actions, backupBusy, grouped = false)
                        io.github.beacon.app.ui.components.SettingsFieldDivider()
                        HistoryArchivePanel(actions, backupBusy, grouped = false)
                    }
                }
            }
            SettingsScreen.RECEPTION -> {
                receptionSettingsItems(
                    runtimeState,
                    actions,
                    receivePlan,
                    receptionForm,
                    onShowReceiveModes,
                    onOperationDetails,
                    busy,
                    polling,
                )
            }
            SettingsScreen.STORAGE -> {
                item { StorageSettingsPanel(runtimeState, actions) }
            }
            SettingsScreen.CONNECTION -> {
                connectionSettingsItems(
                    resources,
                    runtimeState,
                    actions,
                    connectionForm,
                    connectionDirty,
                    busy,
                    context,
                    focus,
                    bindingFormatHint,
                    onFeedback,
                )
            }
            SettingsScreen.BACKGROUND -> {
                item {
                    BackgroundPermissionPanel()
                }
            }
            SettingsScreen.NOTIFICATION_RULES -> {
                item {
                    NotificationSettingsPanel(
                        runtimeState,
                        actions,
                        notificationForm,
                        onRetrySave = { notificationForm.saveRules(actions, quietHoursForm) },
                    )
                }
                item {
                    QuietHoursPanel(
                        runtimeState,
                        actions,
                        quietHoursForm,
                        onSchedule = { onNavigate(SettingsScreen.QUIET_HOURS) },
                        editorSaving = notificationForm.rulesSaving,
                        onRetrySave = { notificationForm.saveRules(actions, quietHoursForm) },
                    )
                }
                item { NotificationSoundPanel() }
            }
            SettingsScreen.NOTIFICATIONS -> {
                notificationPermissionSettingsItems(
                    resources,
                    actions,
                    notificationPermission,
                    pendingReminderCount,
                    onRequestNotificationPermission,
                    onNotificationSettingsUnavailable,
                    busy,
                    context,
                    runtimeState.runtimeConnected,
                    reminderFailed,
                    onRetry = { reminderAttempt++ },
                )
            }
            SettingsScreen.ABOUT -> {
                aboutSettingsItems(resources, context, onOperationDetails)
            }
        }
    }
}
