@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.BuildConfig
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
    onReceivePlanChange: (ReceivePlan) -> Unit,
    onShowReceiveModes: () -> Unit,
    onUiStyleChange: (BeaconUiStyle) -> Unit,
    onThemeModeChange: (BeaconThemeMode) -> Unit,
    onOperationDetails: (String) -> Unit,
    onFeedback: (String) -> Unit,
    onPreviewReport: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onNotificationSettingsUnavailable: () -> Unit,
    updateState: UpdateCheckState,
    onCheckUpdate: () -> Unit,
) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val focus = LocalFocusManager.current
    val busy = runtimeState.messageBusy
    val polling = runtimeState.pollingState
    var pendingReminderCount by
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(
        screen,
        runtimeState.runtimeConnected,
        runtimeState.messageBusy,
    ) {
        if (
            screen == SettingsScreen.NOTIFICATIONS &&
                runtimeState.runtimeConnected &&
                !runtimeState.messageBusy
        )
            actions.pendingReminders { pendingReminderCount = it }
    }
    var developerExpanded by rememberSaveable { mutableStateOf(false) }
    // Resolved here because its only use site is a non-composable callback.
    val bindingFormatHint = stringResource(R.string.binding_format_hint)
    if (screen == SettingsScreen.SERVER_ADMIN) {
        ServerAdminPage(
            runtimeState.configuredEndpoint,
            scroll,
            modifier,
            padding,
            onConnection = { onNavigate(SettingsScreen.CONNECTION) },
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
        when (screen) {
            SettingsScreen.HOME,
            SettingsScreen.APPEARANCE -> {
                item {
                    SettingsGroup(stringResource(R.string.settings_guide_title)) {
                        SettingsRow(
                            stringResource(R.string.settings_guide_steps),
                            stringResource(R.string.settings_guide_steps_hint),
                            BeaconIcons.Guide,
                        ) {
                            onNavigate(SettingsScreen.GUIDE)
                        }
                    }
                }
                item {
                    SettingsGroup(stringResource(R.string.settings_receive_section_title)) {
                        SettingsRow(
                            stringResource(R.string.settings_connection_section_title),
                            runtimeState.configuredEndpoint.ifBlank {
                                stringResource(R.string.settings_first_setup_hint)
                            },
                            BeaconIcons.Server,
                        ) {
                            onNavigate(SettingsScreen.CONNECTION)
                        }
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
                        SettingsRow(
                            stringResource(R.string.settings_notifications_section_title),
                            notificationHint?.let { stringResource(it) }
                                ?: when (notificationPermission) {
                                    NotificationPermissionState.ALLOWED ->
                                        stringResource(
                                            R.string.app_system_notification_allowed_hint
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
                        BeaconDropdownPreference(
                            stringResource(R.string.appearance_style),
                            listOf("Material 3", "MIUIX"),
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
                            stringResource(R.string.admin_entry_hint),
                            BeaconIcons.Server,
                        ) {
                            onNavigate(SettingsScreen.SERVER_ADMIN)
                        }
                        SettingsRow(
                            stringResource(R.string.settings_storage_title),
                            stringResource(R.string.settings_storage_desc),
                            BeaconIcons.Storage,
                        ) {
                            onNavigate(SettingsScreen.STORAGE)
                        }
                        SettingsRow(
                            stringResource(R.string.settings_backup_title),
                            resources.getString(R.string.settings_backup_summary),
                            BeaconIcons.Backup,
                        ) {
                            onNavigate(SettingsScreen.BACKUP)
                        }
                        SettingsRow(
                            stringResource(R.string.settings_advanced_section_title),
                            stringResource(R.string.settings_advanced_section_desc),
                            BeaconIcons.Diagnostics,
                        ) {
                            onNavigate(SettingsScreen.DIAGNOSTICS)
                        }
                        SettingsRow(
                            title = stringResource(R.string.settings_update_title),
                            subtitle =
                                when {
                                    updateState.checking ->
                                        stringResource(R.string.settings_update_checking)
                                    // The dot says "new build"; the subtitle says which one.
                                    updateState.release != null ->
                                        stringResource(
                                            R.string.settings_update_available,
                                            updateState.release?.version.orEmpty(),
                                        )
                                    else ->
                                        stringResource(
                                            R.string.settings_version_label,
                                            BuildConfig.VERSION_NAME,
                                        )
                                },
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
                            stringResource(R.string.settings_about_title),
                            "",
                            BeaconIcons.Info,
                        ) {
                            onNavigate(SettingsScreen.ABOUT)
                        }
                    }
                }
            }
            SettingsScreen.SERVER_ADMIN -> Unit
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
                    SettingsBackupPanel(
                        actions,
                        busy || runtimeState.transportBusy || !runtimeState.runtimeConnected,
                    )
                }
                item {
                    HistoryArchivePanel(
                        actions,
                        busy || runtimeState.transportBusy || !runtimeState.runtimeConnected,
                    )
                }
            }
            SettingsScreen.RECEPTION -> {
                receptionSettingsItems(
                    runtimeState,
                    actions,
                    receivePlan,
                    receptionForm,
                    onReceivePlanChange,
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
                    notificationPermission,
                    onNavigate,
                    busy,
                    context,
                    focus,
                    bindingFormatHint,
                )
            }
            SettingsScreen.BACKGROUND -> {
                item {
                    BackgroundPermissionPanel()
                }
            }
            SettingsScreen.NOTIFICATION_RULES -> {
                item { NotificationSettingsPanel(runtimeState, actions) }
                item { QuietHoursPanel(runtimeState, actions) }
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
                )
            }
            SettingsScreen.DIAGNOSTICS -> {
                diagnosticsSettingsItems(
                    runtimeState,
                    actions,
                    developerExpanded,
                    { developerExpanded = it },
                    onPreviewReport,
                    onOperationDetails,
                    busy,
                    context,
                )
            }
            SettingsScreen.ABOUT -> {
                aboutSettingsItems(resources, context, onOperationDetails)
            }
        }
    }
}
