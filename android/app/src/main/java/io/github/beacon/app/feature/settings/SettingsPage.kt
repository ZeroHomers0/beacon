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
    onSelectReceiveMode: (io.github.beacon.app.domain.model.ReceiveMode) -> Unit,
    onUiStyleChange: (BeaconUiStyle) -> Unit,
    onThemeModeChange: (BeaconThemeMode) -> Unit,
    onOperationDetails: (String) -> Unit,
    onFeedback: (String) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onNotificationSettingsUnavailable: () -> Unit,
    updateState: UpdateCheckState,
    onCheckUpdate: () -> Unit,
    storageSettings: StorageSettingsState = androidx.compose.runtime.remember { StorageSettingsState() },
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
    var reminderLoading by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var reminderFailed by
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var reminderAttempt by
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val reminderLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(reminderLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) reminderAttempt++
        }
        reminderLifecycle.addObserver(observer)
        onDispose { reminderLifecycle.removeObserver(observer) }
    }
    var reminderRequested by androidx.compose.runtime.remember(screen, runtimeState.runtimeConnected, reminderAttempt) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    androidx.compose.runtime.LaunchedEffect(
        reminderAttempt,
        screen,
        runtimeState.runtimeConnected,
        runtimeState.messageBusy,
    ) {
        if (
            screen == SettingsScreen.NOTIFICATIONS &&
                runtimeState.runtimeConnected &&
                !runtimeState.messageBusy && !reminderRequested
        ) {
            reminderRequested = true
            reminderFailed = false
            reminderLoading = true
            actions.pendingReminders(onFailure = { reminderFailed = true; reminderLoading = false }) {
                reminderLoading = false
                pendingReminderCount = it
            }
        } else if (!runtimeState.runtimeConnected) { pendingReminderCount = null; reminderLoading = false }
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
                SettingsScreen.SERVER_TOPICS,
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
    if(screen==SettingsScreen.WIDGETS){
        io.github.beacon.app.widget.WidgetManagerPage(modifier,scroll,padding)
        return
    }
    val needsRuntime = screen in setOf(SettingsScreen.CONNECTION, SettingsScreen.RECEPTION,
        SettingsScreen.NOTIFICATIONS, SettingsScreen.NOTIFICATION_RULES, SettingsScreen.QUIET_HOURS,
        SettingsScreen.STORAGE, SettingsScreen.BACKUP, SettingsScreen.GUIDE)
    var runtimeUnavailable by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(runtimeState.runtimeConnected, screen) {
        runtimeUnavailable = false
        if (needsRuntime && !runtimeState.runtimeConnected) {
            kotlinx.coroutines.delay(1500)
            runtimeUnavailable = true
        }
    }
    io.github.beacon.app.ui.components.SettingsRetryFeedback(
        stringResource(R.string.settings_runtime_disconnected), runtimeUnavailable,
        onRetry = { runtimeUnavailable = false; actions.start(context) })
    if (screen in setOf(SettingsScreen.NOTIFICATION_RULES, SettingsScreen.QUIET_HOURS)) {
        io.github.beacon.app.ui.components.SettingsOperationFeedback(
            stringResource(R.string.settings_data_failed),
            notificationForm.rulesFailure?.let { stringResource(it) }.orEmpty(), failed = true)
    }
    BeaconSettingsList(state = scroll, modifier = modifier, contentPadding = padding) {
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
                        SettingsRow(
                            stringResource(R.string.settings_reception_page_title),
                            stringResource(receiveModeTitle(receivePlan.mode)),
                            receiveModeIcon(receivePlan.mode),
                            iconTint =
                                io.github.beacon.app.ui.components.receiveModeColor(
                                    receivePlan.mode
                                ),
                            onClick = { onNavigate(SettingsScreen.RECEPTION) },
                        )
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
                        SettingsRow(
                            stringResource(R.string.quiet_title),
                            stringResource(R.string.quiet_description),
                            BeaconIcons.QuietHours,
                        ) {
                            onNavigate(SettingsScreen.NOTIFICATION_RULES)
                        }
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
                        SettingsRow(stringResource(R.string.settings_widgets_title),"查看与编辑已添加的桌面小组件",BeaconIcons.Applications){onNavigate(SettingsScreen.WIDGETS)}
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
                            stringResource(R.string.settings_admin_menu_description),
                            BeaconIcons.Server,
                        ) {
                            onNavigate(SettingsScreen.SERVER_ADMIN)
                        }
                        SettingsRow(
                            stringResource(R.string.settings_storage_title),
                            stringResource(R.string.settings_storage_menu_description),
                            BeaconIcons.Storage,
                        ) {
                            onNavigate(SettingsScreen.STORAGE)
                        }
                        SettingsRow(
                            stringResource(R.string.settings_backup_title),
                            stringResource(R.string.settings_backup_menu_description),
                            BeaconIcons.Backup,
                        ) {
                            onNavigate(SettingsScreen.BACKUP)
                        }
                    }
                }
                item {
                    SettingsGroup("帮助与关于") {
                        SettingsRow(
                            title = stringResource(R.string.settings_update_title),
                            compact = true,
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
                        SettingsRow(
                            stringResource(R.string.settings_about_title), "", BeaconIcons.Info,
                            iconTint=MaterialTheme.colorScheme.onSurface, compact=true,
                            onClick={onNavigate(SettingsScreen.ABOUT)},
                        )
                        SettingsRow(
                            stringResource(R.string.settings_guide_title), "", BeaconIcons.Guide,
                            iconTint=MaterialTheme.colorScheme.onSurface, compact=true,
                            onClick={onNavigate(SettingsScreen.GUIDE)},
                        )
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
                        failureMessage = notificationForm.rulesFailure,
                        onRetrySave = { notificationForm.saveRules(actions, quietHoursForm) },
                    )
                }
                if (notificationForm.failed && notificationForm.rulesFailure == null) item {
                    io.github.beacon.app.ui.components.SettingsRetryFeedback(
                        stringResource(R.string.settings_data_failed), true,
                        onRetry = if (runtimeState.runtimeConnected) {
                            { if (notificationForm.loaded) notificationForm.saveRules(actions, quietHoursForm)
                                else notificationForm.attempt++ }
                        } else null)
                }
            }

            SettingsScreen.SERVER_ADMIN,
            SettingsScreen.SERVER_DEVICES,
            SettingsScreen.SERVER_APPLICATIONS,
            SettingsScreen.SERVER_TOPICS,
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
                    }
                }
                item { HistoryArchivePanel(actions, busy || runtimeState.transportBusy || !runtimeState.runtimeConnected) }
            }
            SettingsScreen.RECEPTION -> {
                receptionSettingsItems(
                    runtimeState,
                    actions,
                    receivePlan,
                    receptionForm,
                    onSelectReceiveMode,
                    onOperationDetails,
                    busy,
                    polling,
                )
            }
            SettingsScreen.STORAGE -> {
                item { StorageSettingsPanel(runtimeState, actions, storageSettings) }
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
                        failureMessage = notificationForm.rulesFailure,
                        onRetrySave = { notificationForm.saveRules(actions, quietHoursForm) },
                    )
                }
                item {
                    SettingsGroup(stringResource(R.string.settings_sound_title)) {
                        SettingsRow(stringResource(R.string.settings_open_channels),
                            stringResource(R.string.settings_open_channels_hint), BeaconIcons.Messages) {
                            onNavigate(SettingsScreen.NOTIFICATIONS)
                        }
                    }
                }
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
                    loading = reminderLoading,
                )
            }
            SettingsScreen.ABOUT -> {
                aboutSettingsItems(resources, context, onOperationDetails)
            }
            SettingsScreen.WIDGETS -> Unit
        }
    }
}
