package io.github.beacon.app.ui

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageAction
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.MessageReadFilter
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.feature.settings.AdminUiState
import io.github.beacon.app.feature.settings.ServerAdminViewModel
import io.github.beacon.app.feature.settings.UpdateCheckState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconActionIcon
import io.github.beacon.app.ui.components.BeaconBarBehavior
import io.github.beacon.app.ui.components.BeaconDropdownMenu
import io.github.beacon.app.ui.components.BeaconDropdownMenuItem
import io.github.beacon.app.ui.components.BeaconIconButton
import io.github.beacon.app.ui.components.BeaconMessageTransition
import io.github.beacon.app.ui.components.BeaconPagerToolbar
import io.github.beacon.app.ui.components.BeaconSelectableRow
import io.github.beacon.app.ui.components.BeaconSettingsTransition
import io.github.beacon.app.ui.components.BeaconTopBar
import io.github.beacon.app.ui.components.ReceiveModeButton
import io.github.beacon.app.ui.components.SettingsScreen
import io.github.beacon.app.ui.icons.BeaconIcons

/**
 * The whole top bar: title, navigation icon, and the per-page action row.
 *
 * It lives apart from [BeaconApp] because the action row is a wide, branchy `when` over page and
 * settings screen; keeping it in the same function as the pager and the back handlers made a
 * 1100-line composable where nothing could be read in isolation. The state is passed in rather
 * than remembered here, so [BeaconApp] still owns the single source of truth for navigation.
 */
@Composable
internal fun BeaconTopBarArea(
    ui: BeaconUiState,
    runtimeState: RuntimeState,
    actions: RuntimeActions,
    adminState: AdminUiState,
    adminModel: ServerAdminViewModel,
    updateState: UpdateCheckState,
    detailTransition: Transition<IncomingMessage?>,
    settingsTransition: Transition<SettingsScreen>,
    settingsMotion: SeekableTransitionState<SettingsScreen>,
    pager: PagerState,
    barBehavior: BeaconBarBehavior,
    sharedTitleBar: Boolean,
    navigate: (Int) -> Unit,
) {
    // Both change per recomposition and are cheap to read, so deriving them here keeps the call
    // site from forwarding two more parameters.
    val busy = runtimeState.messageBusy
    val polling = runtimeState.pollingState

    BeaconMessageTransition(detailTransition) { displayedDetail ->
        val renderSettingsBar: @Composable (Int, SettingsScreen) -> Unit =
            { barPage, displayedSettingsScreen ->
                BeaconTopBar(
                    if (displayedDetail != null)
                        stringResource(R.string.ui_message_detail_title)
                    else if (barPage == 1)
                        stringResource(displayedSettingsScreen.titleRes)
                    else if (barPage == 0 && ui.selectionMode)
                        stringResource(
                            R.string.msg_selected_count_label,
                            ui.selectedKeys.size,
                        )
                    else if (sharedTitleBar && barPage == 0) ""
                    else stringResource(ui.pages[barPage]),
                    (sharedTitleBar && barPage == 0) ||
                        (barPage == 0 && ui.selectionMode) ||
                        displayedDetail != null ||
                        (barPage == 1 &&
                            displayedSettingsScreen != SettingsScreen.HOME),
                    barBehavior,
                    navigationIcon = {
                        if (
                            displayedDetail == null &&
                                barPage == 0 &&
                                ui.selectionMode
                        )
                            BeaconActionIcon(
                                BeaconIcons.Close,
                                stringResource(
                                    R.string.ui_exit_multi_select_action
                                ),
                            ) {
                                ui.selectionMode = false
                                ui.selectedKeys = emptyList()
                                ui.selectionHint = ""
                            }
                        else if (displayedDetail != null)
                            BeaconIconButton(
                                onClick = { ui.closeDetail(actions) }
                            ) {
                                Icon(
                                    BeaconIcons.Back,
                                    stringResource(
                                        R.string.ui_back_to_messages_action
                                    ),
                                )
                            }
                        else if (barPage == 1)
                            BeaconIconButton(
                                onClick = {
                                    navigate(
                                        if (
                                            displayedSettingsScreen ==
                                                SettingsScreen.HOME
                                        )
                                            0
                                        else 1
                                    )
                                }
                            ) {
                                Icon(
                                    BeaconIcons.Back,
                                    stringResource(
                                        if (
                                            displayedSettingsScreen ==
                                                SettingsScreen.HOME
                                        )
                                            R.string.ui_back_to_messages_action
                                        else R.string.ui_back_to_settings_action
                                    ),
                                )
                            }
                        else if (barPage == 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ReceiveModeButton(
                                    polling.shouldSchedule &&
                                        runtimeState.serverReachable == true &&
                                        (ui.receivePlan.mode !=
                                            io.github.beacon.app.domain.model
                                                .ReceiveMode
                                                .REALTIME ||
                                            runtimeState.realtimeRunning),
                                    ui.receivePlan.mode,
                                    onClick = {
                                        if (
                                            runtimeState.runtimeConnected &&
                                                runtimeState
                                                    .receivePlanLoaded &&
                                                !busy
                                        )
                                            ui.receiveModesOpen = true
                                    },
                                    onLongClick = {
                                        ui.page = 1
                                        ui.navigateSettings(
                                            if (
                                                runtimeState.configuredEndpoint
                                                    .isBlank() ||
                                                    runtimeState
                                                        .serverReachable ==
                                                        false
                                            )
                                                SettingsScreen.CONNECTION
                                            else SettingsScreen.RECEPTION
                                        )
                                    },
                                    serverIssue =
                                        runtimeState.connectionSummaryLoaded &&
                                            (runtimeState.configuredEndpoint
                                                .isBlank() ||
                                                runtimeState.serverReachable ==
                                                    false),
                                    checkingServer =
                                        runtimeState.serverChecking,
                                    visible = ui.page == 0 && pager.currentPage == 0 &&
                                        !pager.isScrollInProgress && displayedDetail == null,
                                    connected =
                                        runtimeState.serverReachable == true &&
                                            (ui.receivePlan.mode !=
                                                io.github.beacon.app.domain
                                                    .model
                                                    .ReceiveMode
                                                    .REALTIME ||
                                                runtimeState.realtimeRunning),
                                )
                            }
                        }
                    },
                    actions = {
                        if (
                            displayedDetail == null &&
                                barPage == 0 &&
                                ui.selectionMode
                        ) {
                            BeaconActionIcon(
                                BeaconIcons.DoneAll,
                                stringResource(R.string.ui_mark_read_action),
                                !busy && ui.selectedKeys.isNotEmpty(),
                            ) {
                                actions.manageMessages(
                                    MessageAction.MARK_READ,
                                    ui.selectedKeys,
                                    onComplete = {
                                        ui.selectedKeys = emptyList()
                                    },
                                )
                            }
                            BeaconActionIcon(
                                BeaconIcons.Messages,
                                stringResource(R.string.ui_mark_unread_action),
                                !busy && ui.selectedKeys.isNotEmpty(),
                            ) {
                                actions.manageMessages(
                                    MessageAction.MARK_UNREAD,
                                    ui.selectedKeys,
                                    onComplete = {
                                        ui.selectedKeys = emptyList()
                                    },
                                )
                            }
                            BeaconActionIcon(
                                BeaconIcons.Delete,
                                stringResource(
                                    R.string.ui_delete_selected_action
                                ),
                                !busy && ui.selectedKeys.isNotEmpty(),
                            ) {
                                ui.pendingDelete = ui.selectedKeys.toList()
                            }
                            val visibleKeys =
                                runtimeState.messages.map {
                                    MessageKey(it.server, it.id)
                                }
                            val allSelected =
                                visibleKeys.isNotEmpty() &&
                                    ui.selectedKeys.containsAll(visibleKeys)
                            val maxSelectionWarning =
                                stringResource(
                                    R.string.ui_max_selection_warning
                                )
                            BeaconActionIcon(
                                if (allSelected) BeaconIcons.Close
                                else BeaconIcons.Check,
                                if (allSelected)
                                    stringResource(
                                        R.string.ui_deselect_page_action
                                    )
                                else
                                    stringResource(
                                        R.string.ui_select_page_action
                                    ),
                                !busy,
                            ) {
                                val merged =
                                    (ui.selectedKeys + visibleKeys).distinct()
                                if (allSelected)
                                    ui.selectedKeys =
                                        ui.selectedKeys - visibleKeys.toSet()
                                else if (merged.size <= 100) {
                                    ui.selectedKeys = merged
                                    ui.selectionHint = ""
                                } else ui.selectionHint = maxSelectionWarning
                            }
                        } else if (displayedDetail == null && barPage == 0) {
                            BeaconActionIcon(
                                BeaconIcons.ReadAll,
                                stringResource(
                                    R.string.ui_mark_all_read_action
                                ),
                                enabled =
                                    runtimeState.runtimeConnected &&
                                        runtimeState.messagesLoaded &&
                                        !busy,
                            ) {
                                actions.markAllRead()
                            }
                            io.github.beacon.app.ui.components.BeaconRefreshAction(
                                stringResource(R.string.action_sync_now),
                                runtimeState.runtimeConnected && !busy,
                                runtimeState.transportBusy,
                            ) {
                                actions.transport()
                            }
                            Box {
                                BeaconActionIcon(
                                    BeaconIcons.Filter,
                                    stringResource(
                                        R.string.ui_filter_messages_title
                                    ),
                                    enabled = !busy,
                                ) {
                                    ui.filtersOpen = true
                                }
                                BeaconDropdownMenu(
                                    ui.filtersOpen,
                                    { ui.filtersOpen = false },
                                ) {
                                    MessageReadFilter.entries.forEach { filter
                                        ->
                                        BeaconDropdownMenuItem(
                                            text = {
                                                BeaconSelectableRow(
                                                    stringResource(
                                                        when (filter) {
                                                            MessageReadFilter
                                                                .ALL ->
                                                                R.string
                                                                    .ui_filter_all_label
                                                            MessageReadFilter
                                                                .UNREAD ->
                                                                R.string
                                                                    .msg_unread_label
                                                            MessageReadFilter
                                                                .READ ->
                                                                R.string
                                                                    .msg_read_label
                                                        }
                                                    ),
                                                    filter == ui.readFilter,
                                                )
                                            },
                                            onClick = {
                                                ui.filtersOpen = false
                                                ui.applyFilter(
                                                    actions,
                                                    ui.search,
                                                    filter,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                            BeaconActionIcon(
                                BeaconIcons.Settings,
                                stringResource(R.string.ui_nav_settings_title),
                                showBadge = updateState.hasUnreadUpdate,
                            ) {
                                ui.navigateSettings(SettingsScreen.HOME)
                                navigate(1)
                            }
                        } else if (
                            displayedDetail == null &&
                                barPage == 1 &&
                                displayedSettingsScreen ==
                                    SettingsScreen.SERVER_ADMIN
                        ) {
                            io.github.beacon.app.ui.components.BeaconRefreshAction(
                                stringResource(R.string.admin_refresh),
                                adminState.loggedIn && !adminState.busy,
                                adminState.busy,
                            ) {
                                adminModel.refresh()
                            }
                        } else if (
                            displayedDetail == null &&
                                barPage == 1 &&
                                displayedSettingsScreen in
                                    setOf(
                                        SettingsScreen.SERVER_DEVICES,
                                        SettingsScreen.SERVER_APPLICATIONS,
                                    )
                        ) {
                            io.github.beacon.app.ui.components.BeaconRefreshAction(
                                stringResource(R.string.admin_refresh),
                                adminState.loggedIn && !adminState.busy,
                                adminState.busy,
                            ) {
                                adminModel.refresh()
                            }
                            BeaconActionIcon(
                                BeaconIcons.Add,
                                stringResource(
                                    if (
                                        displayedSettingsScreen ==
                                            SettingsScreen.SERVER_DEVICES
                                    )
                                        R.string.admin_create_binding
                                    else R.string.admin_create_application
                                ),
                                adminState.loggedIn && !adminState.busy,
                            ) {
                                adminModel.showCreation()
                            }
                        } else if (
                            displayedDetail == null &&
                                barPage == 1 &&
                                displayedSettingsScreen ==
                                    SettingsScreen.RECEPTION
                        ) {
                            val form = ui.receptionForm
                            io.github.beacon.app.ui.components.BeaconSaveAction(
                                stringResource(R.string.action_save),
                                dirty = form.edited && form.differsFrom(
                                    runtimeState.pollingState.config,
                                    runtimeState.receivePlan,
                                ),
                                saving = busy,
                                enabled = runtimeState.runtimeConnected &&
                                    runtimeState.receivePlanLoaded &&
                                    !busy &&
                                    form.edited &&
                                    form.scheduleValid &&
                                    form.differsFrom(
                                        runtimeState.pollingState.config,
                                        runtimeState.receivePlan,
                                    ),
                            ) {
                                actions.saveReception(
                                    form.pendingPlan(),
                                    form.applyTo(
                                        runtimeState.pollingState.config
                                    ),
                                )
                            }
                        } else if (
                            displayedDetail == null &&
                                barPage == 1 &&
                                displayedSettingsScreen in
                                    setOf(
                                        SettingsScreen.NOTIFICATION_RULES,
                                        SettingsScreen.QUIET_HOURS,
                                    )
                        ) {
                            io.github.beacon.app.ui.components.BeaconSaveAction(
                                stringResource(R.string.action_save),
                                dirty = ui.quietHoursForm.changed || ui.notificationForm.changed,
                                saving = busy || ui.quietHoursForm.saving ||
                                    ui.notificationForm.saving || ui.notificationForm.rulesSaving,
                                enabled = runtimeState.runtimeConnected &&
                                    !busy &&
                                    ui.quietHoursForm.loaded &&
                                    ui.quietHoursForm.valid &&
                                    !ui.quietHoursForm.saving &&
                                    ui.notificationForm.loaded &&
                                    !ui.notificationForm.saving &&
                                    !ui.notificationForm.rulesSaving &&
                                    (ui.quietHoursForm.changed ||
                                        ui.notificationForm.changed),
                            ) {
                                ui.notificationForm.saveRules(
                                    actions,
                                    ui.quietHoursForm,
                                )
                            }
                        }
                    },
                )
            }
        val renderBar: @Composable (Int) -> Unit = { barPage ->
            if (barPage == 1 && displayedDetail == null)
                BeaconSettingsTransition(
                    settingsTransition,
                    ui.settingsNavigatingBack ||
                        settingsMotion.targetState == ui.settingsBackTarget,
                ) { screen ->
                    renderSettingsBar(barPage, screen)
                }
            else renderSettingsBar(barPage, ui.settingsScreen)
        }
        if (displayedDetail != null) renderBar(ui.page)
        else BeaconPagerToolbar(pager, renderBar)
    }
}

