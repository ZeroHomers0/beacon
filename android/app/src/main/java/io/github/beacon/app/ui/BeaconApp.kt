@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.ui

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.domain.model.MessageAction
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.MessageReadFilter
import io.github.beacon.app.feature.messages.MessageDetailScreen
import io.github.beacon.app.feature.messages.MessagesPage
import io.github.beacon.app.feature.settings.ReceiveModeDialog
import io.github.beacon.app.feature.settings.SettingsPage
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconActionIcon
import io.github.beacon.app.ui.components.BeaconAlertDialog
import io.github.beacon.app.ui.components.BeaconButton
import io.github.beacon.app.ui.components.BeaconDropdownMenu
import io.github.beacon.app.ui.components.BeaconDropdownMenuItem
import io.github.beacon.app.ui.components.BeaconIconButton
import io.github.beacon.app.ui.components.BeaconNavigation
import io.github.beacon.app.ui.components.BeaconPagerToolbar
import io.github.beacon.app.ui.components.BeaconScaffold
import io.github.beacon.app.ui.components.BeaconSelectableRow
import io.github.beacon.app.ui.components.BeaconSettingsTransition
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconTextButton
import io.github.beacon.app.ui.components.BeaconTopBar
import io.github.beacon.app.ui.components.NotificationPermissionState
import io.github.beacon.app.ui.components.ReceiveModeButton
import io.github.beacon.app.ui.components.SettingsScreen
import io.github.beacon.app.ui.components.rememberBeaconBarBehavior
import io.github.beacon.app.ui.components.settingsGestureFraction
import io.github.beacon.app.ui.components.userOperationFeedback
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.BeaconTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Below this window height the list pages move their title into the sticky search row and shrink
 * the top bar, so a short window (landscape, split screen) keeps both the title and the search
 * field without a two-band header.
 */
private val SHARED_TITLE_MAX_HEIGHT = 560.dp

@Composable
fun BeaconApp(
    viewModel: BeaconViewModel,
    notificationPermission: NotificationPermissionState,
    notificationHint: Int?,
    onRequestNotificationPermission: () -> Unit,
    onNotificationSettingsUnavailable: () -> Unit,
) {
    val actions: RuntimeActions = viewModel
    val context = androidx.compose.ui.platform.LocalContext.current
    val ui = viewModel.uiState
    val runtimeState by actions.state.collectAsStateWithLifecycle()
    LaunchedEffect(runtimeState.connectionSummaryLoaded) {
        if (runtimeState.connectionSummaryLoaded) {
            val welcome = context.getSharedPreferences("first_use_guide", 0)
            if (!welcome.getBoolean("welcome_shown", false)) {
                welcome.edit().putBoolean("welcome_shown", true).apply()
                if (runtimeState.configuredEndpoint.isBlank() && ui.pendingNotification == null) {
                    ui.page = 1
                    ui.navigateSettings(SettingsScreen.HOME)
                }
            }
        }
    }
    LaunchedEffect(
        ui.pendingNotification,
        runtimeState.runtimeConnected,
        runtimeState.messageBusy,
    ) {
        val key = ui.pendingNotification
        if (key != null && runtimeState.runtimeConnected && !runtimeState.messageBusy) {
            actions.openMessage(key) {
                if (ui.pendingNotification == key) ui.pendingNotification = null
            }
        }
    }
    LaunchedEffect(
        runtimeState.selectedMessage,
        ui.pendingNotification,
        runtimeState.runtimeConnected,
        runtimeState.messageBusy,
    ) {
        val message = runtimeState.selectedMessage
        if (message != null) ui.detailKey = MessageKey(message.server, message.id)
        else if (
            ui.pendingNotification == null &&
                runtimeState.runtimeConnected &&
                !runtimeState.messageBusy
        )
            ui.detailKey = null
    }
    LaunchedEffect(runtimeState.receivePlan) { ui.receivePlan = runtimeState.receivePlan }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // The poll is a cross-process read of the runtime service, so it only runs while this
    // window is actually interactive. Returning to the foreground re-enters the block and
    // refreshes immediately, which covers a paused-but-not-stopped activity as well.
    LaunchedEffect(actions, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                actions.receivePlan()
                delay(5000)
            }
        }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        // The saved server is re-probed on every return to the foreground, but the toolbar entry
        // keeps the last verdict while it runs, so a healthy server never flashes "checking".
        actions.autoCheckServer()
        actions.refreshPolling()
    }

    BeaconTheme(ui.uiStyle, ui.themeMode) {
        val pager = rememberPagerState(initialPage = ui.page) { 2 }
        LaunchedEffect(ui.page) {
            pager.animateScrollToPage(
                ui.page,
                animationSpec =
                    spring(
                        stiffness = 322.2f,
                        dampingRatio = 32.31f / (2f * kotlin.math.sqrt(322.2f)),
                        visibilityThreshold = 0.5f,
                    ),
            )
        }
        LaunchedEffect(runtimeState.configuredEndpoint, runtimeState.connectionSummaryLoaded) {
            if (!ui.connectionForm.connectionEdited)
                ui.connectionForm.endpoint = runtimeState.configuredEndpoint
        }
        val connectionDirty = ui.connectionDirty(runtimeState)
        val snackbar = remember { SnackbarHostState() }
        // Clipboard labels are consumed inside onClick lambdas, so they resolve outside those
        // scopes.
        val redactedDiagLabel = stringResource(R.string.ui_redacted_diag_title)
        val clipboardBodyLabel = stringResource(R.string.ui_widget_body_label)
        val clipboardTitleLabel = stringResource(R.string.ui_widget_title_label)
        ui.operationDetails?.let { details ->
            BeaconAlertDialog(
                onDismissRequest = { ui.operationDetails = null },
                title = { BeaconText(stringResource(R.string.ui_operation_result_title)) },
                text = { BeaconText(details) },
                confirmButton = {
                    BeaconButton(onClick = { ui.operationDetails = null }) {
                        BeaconText(stringResource(R.string.ui_got_it_action))
                    }
                },
                dismissButton = {
                    BeaconTextButton(onClick = { ui.operationDetails = null }) {
                        BeaconText(stringResource(R.string.action_close))
                    }
                },
            )
        }
        LaunchedEffect(runtimeState.pollingState.config) {
            if (
                !ui.receptionForm.edited ||
                    !ui.receptionForm.differsFrom(runtimeState.pollingState.config)
            )
                ui.receptionForm.reset(runtimeState.pollingState.config)
        }
        ui.pendingReceiveMode?.let { mode ->
            BeaconAlertDialog(
                onDismissRequest = { ui.pendingReceiveMode = null },
                title = { BeaconText(stringResource(R.string.receive_unsaved_title)) },
                text = { BeaconText(stringResource(R.string.receive_unsaved_hint)) },
                confirmButton = {
                    BeaconButton(
                        onClick = {
                            ui.receptionForm.reset(runtimeState.pollingState.config)
                            actions.receivePlan(ui.receivePlan.copy(mode = mode))
                            ui.pendingReceiveMode = null
                        }
                    ) {
                        BeaconText(stringResource(R.string.receive_discard_switch))
                    }
                },
                dismissButton = {
                    BeaconTextButton(
                        onClick = {
                            ui.pendingReceiveMode = null
                            ui.page = 1
                            ui.navigateSettings(SettingsScreen.RECEPTION)
                        }
                    ) {
                        BeaconText(stringResource(R.string.receive_return_save))
                    }
                },
            )
        }
        if (ui.receiveModesOpen)
            ReceiveModeDialog(
                ui.receivePlan.mode,
                onSelect = { mode ->
                    if (
                        mode != ui.receivePlan.mode &&
                            ui.receptionForm.edited &&
                            ui.receptionForm.differsFrom(runtimeState.pollingState.config)
                    ) {
                        ui.pendingReceiveMode = mode
                    } else {
                        actions.receivePlan(ui.receivePlan.copy(mode = mode))
                    }
                    ui.receiveModesOpen = false
                },
                onDismiss = { ui.receiveModesOpen = false },
            )
        val messageScroll = rememberLazyListState()
        val settingsScrollStates = SettingsScreen.entries.associateWith { rememberLazyListState() }
        val uiScope = rememberCoroutineScope()
        val focus = LocalFocusManager.current
        // Clearing focus needs the Compose owner, so wrap the state-holder helper.
        val navigate: (Int) -> Unit = { index ->
            focus.clearFocus()
            ui.navigate(actions, runtimeState, index)
        }
        val settingsMotion = remember { SeekableTransitionState(ui.settingsScreen) }
        val settingsTransition = rememberTransition(settingsMotion, label = "Settings navigation")
        LaunchedEffect(ui.settingsScreen) { settingsMotion.animateTo(ui.settingsScreen) }
        val predictiveSettingsBack =
            ui.page == 1 &&
                ui.settingsScreen != SettingsScreen.HOME &&
                runtimeState.selectedMessage == null &&
                !connectionDirty &&
                !runtimeState.messageBusy
        PredictiveBackHandler(enabled = predictiveSettingsBack) { events ->
            val original = ui.settingsScreen
            try {
                events.collect { event ->
                    settingsMotion.seekTo(
                        settingsGestureFraction(event.progress),
                        ui.settingsBackTarget,
                    )
                }
                navigate(1)
            } catch (_: kotlinx.coroutines.CancellationException) {
                uiScope.launch { settingsMotion.animateTo(original) }
            }
        }
        BackHandler(
            enabled =
                !predictiveSettingsBack &&
                    (runtimeState.selectedMessage != null ||
                        ui.page != 0 ||
                        ui.selectionMode ||
                        ui.filtersOpen)
        ) {
            when {
                runtimeState.selectedMessage != null -> ui.closeDetail(actions)
                ui.page == 1 && ui.settingsScreen != SettingsScreen.HOME -> navigate(1)
                ui.page != 0 -> navigate(0)
                ui.selectionMode -> {
                    ui.selectionMode = false
                    ui.selectedKeys = emptyList()
                    ui.selectionHint = ""
                }
                ui.filtersOpen -> ui.filtersOpen = false
                else -> Unit
            }
        }
        LaunchedEffect(
            runtimeState.messagePageIndex == 0,
            runtimeState.messageQuery.serverId,
            runtimeState.messageQuery.search,
            runtimeState.messageQuery.readFilter,
        ) {
            if (runtimeState.messagePageIndex == 0) messageScroll.scrollToItem(0)
        }
        LaunchedEffect(ui.search, runtimeState.messageBusy) {
            delay(350)
            if (!runtimeState.messageBusy && ui.search != runtimeState.messageQuery.search)
                ui.applyFilter(actions, ui.search, ui.readFilter)
        }
        ui.supportPreview?.let { report ->
            BeaconAlertDialog(
                onDismissRequest = { ui.supportPreview = null },
                title = { BeaconText(stringResource(R.string.ui_redacted_report_title)) },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            BeaconText(report, style = MaterialTheme.typography.bodySmall)
                        }
                        if (ui.reportCopied)
                            BeaconText(stringResource(R.string.ui_report_copied_hint))
                    }
                },
                confirmButton = {
                    BeaconTextButton(
                        onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText(redactedDiagLabel, report))
                            ui.reportCopied = true
                        }
                    ) {
                        BeaconText(stringResource(R.string.diag_copy_report_action))
                    }
                },
                dismissButton = {
                    BeaconTextButton(onClick = { ui.supportPreview = null }) {
                        BeaconText(stringResource(R.string.action_close))
                    }
                },
            )
        }
        ui.pendingPage?.let { destination ->
            BeaconAlertDialog(
                onDismissRequest = { ui.pendingPage = null },
                title = { BeaconText(stringResource(R.string.ui_discard_connection_title)) },
                text = { BeaconText(stringResource(R.string.ui_unsaved_connection_hint)) },
                confirmButton = {
                    BeaconButton(
                        onClick = {
                            ui.connectionForm.endpoint = runtimeState.configuredEndpoint
                            ui.connectionForm.bindingCode = ""
                            ui.connectionForm.connectionError = ""
                            ui.connectionForm.connectionEdited = false
                            ui.pendingPage = null
                            ui.finishNavigation(destination)
                        }
                    ) {
                        BeaconText(stringResource(R.string.ui_discard_and_leave_action))
                    }
                },
                dismissButton = {
                    BeaconTextButton(onClick = { ui.pendingPage = null }) {
                        BeaconText(stringResource(R.string.ui_continue_editing_action))
                    }
                },
            )
        }
        ui.pendingDelete?.let { keys ->
            BeaconAlertDialog(
                onDismissRequest = { ui.pendingDelete = null },
                title = {
                    BeaconText(stringResource(R.string.msg_delete_confirm_title, keys.size))
                },
                text = {
                    BeaconText(
                        if (keys.size == 1) stringResource(R.string.ui_delete_undo_hint)
                        else stringResource(R.string.ui_delete_multi_warning_hint)
                    )
                },
                confirmButton = {
                    BeaconButton(
                        enabled = !runtimeState.messageBusy,
                        onClick = {
                            ui.pendingDelete = null
                            actions.manageMessages(
                                MessageAction.DELETE,
                                keys,
                                onComplete = {
                                    ui.selectedKeys = emptyList()
                                    uiScope.launch { messageScroll.scrollToItem(0) }
                                },
                            )
                        },
                    ) {
                        BeaconText(stringResource(R.string.ui_confirm_delete_action))
                    }
                },
                dismissButton = {
                    BeaconTextButton(onClick = { ui.pendingDelete = null }) {
                        BeaconText(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
        val detail = runtimeState.selectedMessage
        val busy = runtimeState.messageBusy
        val polling = runtimeState.pollingState
        val feedback = userOperationFeedback(runtimeState.status)
        val feedbackTimeout =
            androidx.compose.ui.platform.LocalAccessibilityManager.current
                ?.calculateRecommendedTimeoutMillis(
                    2200L,
                    containsIcons = true,
                    containsText = true,
                    containsControls = false,
                ) ?: 2200L
        LaunchedEffect(runtimeState.status) {
            if (runtimeState.status.showsSnackbar && feedback.isNotBlank()) {
                snackbar.currentSnackbarData?.dismiss()
                val display = launch {
                    snackbar.showSnackbar(feedback, duration = SnackbarDuration.Indefinite)
                }
                kotlinx.coroutines.delay(feedbackTimeout)
                snackbar.currentSnackbarData?.dismiss()
                display.join()
            }
        }
        // A short window (landscape, split screen) folds the page title into the search row. The
        // container size is read instead of Configuration.screenHeightDp so a resize is picked up
        // without waiting for a configuration change.
        val windowInfo = LocalWindowInfo.current
        val windowHeightDp = with(LocalDensity.current) { windowInfo.containerSize.height.toDp() }
        val sharedTitleBar =
            windowHeightDp > 0.dp &&
                windowHeightDp < SHARED_TITLE_MAX_HEIGHT &&
                (ui.page == 0) &&
                detail == null &&
                !ui.selectionMode
        // A short window folds the title into the search row; opening the IME preserves this
        // layout.
        val sharedTitleLabel = if (sharedTitleBar) stringResource(ui.pages[ui.page]) else null
        val barBehavior = rememberBeaconBarBehavior()
        val compactBar =
            sharedTitleBar ||
                (ui.page == 0 && ui.selectionMode) ||
                detail != null ||
                (ui.page == 1 && ui.settingsScreen != SettingsScreen.HOME)
        val landscape =
            androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        Row(Modifier.fillMaxSize()) {
            if (landscape && detail == null)
                BeaconNavigation(ui.page, ui.pages, rail = true) { index ->
                    if (ui.page != index) navigate(index)
                }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                BeaconScaffold(
                    snackbar = snackbar,
                    topBar = {
                        val renderSettingsBar: @Composable (Int, SettingsScreen) -> Unit =
                            { barPage, displayedSettingsScreen ->
                                BeaconTopBar(
                                    if (detail != null)
                                        stringResource(R.string.ui_message_detail_title)
                                    else if (barPage == 1)
                                        stringResource(displayedSettingsScreen.titleRes)
                                    else if (barPage == 0 && ui.selectionMode)
                                        stringResource(
                                            R.string.msg_selected_count_label,
                                            ui.selectedKeys.size,
                                        )
                                    else if (sharedTitleBar) ""
                                    else stringResource(ui.pages[barPage]),
                                    sharedTitleBar ||
                                        (barPage == 0 && ui.selectionMode) ||
                                        detail != null ||
                                        (barPage == 1 &&
                                            displayedSettingsScreen != SettingsScreen.HOME),
                                    barBehavior,
                                    navigationIcon = {
                                        if (detail == null && barPage == 0 && ui.selectionMode)
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
                                        else if (detail != null)
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
                                        else if (
                                            barPage == 1 &&
                                                displayedSettingsScreen != SettingsScreen.HOME
                                        )
                                            BeaconIconButton(onClick = { navigate(1) }) {
                                                Icon(
                                                    BeaconIcons.Back,
                                                    stringResource(
                                                        R.string.ui_back_to_settings_action
                                                    ),
                                                )
                                            }
                                        else if (barPage == 0) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                                            runtimeState.configuredEndpoint
                                                                .isBlank() ||
                                                                runtimeState.serverReachable ==
                                                                    false ||
                                                                runtimeState.serverChecking
                                                        ) {
                                                            ui.page = 1
                                                            ui.navigateSettings(SettingsScreen.CONNECTION)
                                                        } else ui.receiveModesOpen = true
                                                    },
                                                    onLongClick = {
                                                        ui.page = 1
                                                        ui.navigateSettings(
                                                            if (
                                                                runtimeState.configuredEndpoint
                                                                    .isBlank() ||
                                                                    runtimeState.serverReachable ==
                                                                        false
                                                            )
                                                                SettingsScreen.CONNECTION
                                                            else SettingsScreen.RECEPTION)
                                                    },
                                                    serverIssue =
                                                        runtimeState.connectionSummaryLoaded &&
                                                            (runtimeState.configuredEndpoint
                                                                .isBlank() ||
                                                                runtimeState.serverReachable ==
                                                                    false),
                                                    checkingServer = runtimeState.serverChecking,
                                                )
                                            }
                                        }
                                    },
                                    actions = {
                                        if (detail == null && barPage == 0 && ui.selectionMode) {
                                            BeaconActionIcon(
                                                BeaconIcons.DoneAll,
                                                stringResource(R.string.ui_mark_read_action),
                                                !busy && ui.selectedKeys.isNotEmpty(),
                                            ) {
                                                actions.manageMessages(
                                                    MessageAction.MARK_READ,
                                                    ui.selectedKeys,
                                                    onComplete = { ui.selectedKeys = emptyList() },
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
                                                    onComplete = { ui.selectedKeys = emptyList() },
                                                )
                                            }
                                            BeaconActionIcon(
                                                BeaconIcons.Delete,
                                                stringResource(R.string.ui_delete_selected_action),
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
                                                stringResource(R.string.ui_max_selection_warning)
                                            BeaconActionIcon(
                                                if (allSelected) BeaconIcons.Close
                                                else BeaconIcons.Check,
                                                if (allSelected)
                                                    stringResource(R.string.ui_deselect_page_action)
                                                else stringResource(R.string.ui_select_page_action),
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
                                        } else if (detail == null && barPage == 0) {
                                            if (busy)
                                                Box(
                                                    Modifier.size(48.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    CircularProgressIndicator(
                                                        Modifier.size(22.dp),
                                                        strokeWidth = 2.dp,
                                                    )
                                                }
                                            else
                                                BeaconActionIcon(
                                                    BeaconIcons.Sync,
                                                    stringResource(R.string.action_sync_now),
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
                                                    MessageReadFilter.entries.forEach { filter ->
                                                        BeaconDropdownMenuItem(
                                                            text = {
                                                                BeaconSelectableRow(
                                                                    stringResource(
                                                                        when (filter) {
                                                                            MessageReadFilter.ALL ->
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
                                        }
                                    },
                                )
                            }
                        val renderBar: @Composable (Int) -> Unit = { barPage ->
                            if (barPage == 1 && detail == null)
                                BeaconSettingsTransition(settingsTransition, ui.settingsNavigatingBack || settingsMotion.targetState == ui.settingsBackTarget) { screen ->
                                    renderSettingsBar(barPage, screen)
                                }
                            else renderSettingsBar(barPage, ui.settingsScreen)
                        }
                        if (detail != null) renderBar(ui.page)
                        else BeaconPagerToolbar(pager, renderBar)
                    },
                    bottomBar = {
                        if (detail == null && !landscape)
                            BeaconNavigation(ui.page, ui.pages) { index ->
                                if (ui.page != index) navigate(index)
                            }
                    },
                ) { insets ->
                    // Keyboard overlays the fixed layout; Scaffold only supplies system-bar/page
                    // insets.
                    val listModifier =
                        Modifier.fillMaxSize()
                            .padding(insets)
                            .then(
                                if (compactBar) Modifier
                                else Modifier.nestedScroll(barBehavior.connection(ui.uiStyle))
                            )
                    val edge = if (ui.uiStyle == BeaconUiStyle.MIUIX) 12.dp else 16.dp
                    val listPadding =
                        PaddingValues(start = edge, end = edge, top = 12.dp, bottom = 24.dp)
                    if (detail != null)
                        MessageDetailScreen(
                            detail,
                            listModifier,
                            onCopy = { body ->
                                context
                                    .getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(
                                        ClipData.newPlainText(
                                            if (body) clipboardBodyLabel else clipboardTitleLabel,
                                            if (body) detail.body else detail.title,
                                        )
                                    )
                            },
                            onDelete = {
                                ui.pendingDelete = listOf(MessageKey(detail.server, detail.id))
                            },
                            busy = busy,
                            onShare = {
                                io.github.beacon.app.feature.messages.shareMessage(context, detail)
                            },
                            onRemind = {
                                actions.remindMessage(MessageKey(detail.server, detail.id))
                            },
                            onToggleRead = {
                                actions.manageMessages(
                                    if (detail.isRead) MessageAction.MARK_UNREAD
                                    else MessageAction.MARK_READ,
                                    listOf(MessageKey(detail.server, detail.id)),
                                    preservePage = true,
                                )
                            },
                        )
                    else
                        HorizontalPager(
                            state = pager,
                            modifier = Modifier.fillMaxSize(),
                            userScrollEnabled = false,
                            beyondViewportPageCount = 1,
                            overscrollEffect = null,
                        ) { visiblePage ->
                            when (visiblePage) {
                                0 ->
                                    MessagesPage(
                                        messageScroll,
                                        Modifier.fillMaxSize().padding(insets),
                                        listPadding,
                                        runtimeState,
                                        actions,
                                        ui.search,
                                        ui.readFilter,
                                        ui.selectionMode,
                                        ui.selectedKeys,
                                        ui.selectionHint,
                                        scrollModifier =
                                            if (compactBar) Modifier
                                            else
                                                Modifier.nestedScroll(
                                                    barBehavior.connection(ui.uiStyle)
                                                ),
                                        sharedTitle = sharedTitleLabel,
                                        onSearch = { ui.search = it },
                                        onApplyFilter = { text, filter ->
                                            ui.applyFilter(actions, text, filter)
                                        },
                                        onConnectionSettings = {
                                            ui.page = 1
                                            ui.navigateSettings(SettingsScreen.CONNECTION)
                                        },
                                        onEnterSelection = { key ->
                                            if (!ui.selectionMode) {
                                                ui.selectionMode = true
                                                ui.selectedKeys = listOf(key)
                                            } else if (
                                                key !in ui.selectedKeys &&
                                                    ui.selectedKeys.size < 100
                                            ) {
                                                ui.selectedKeys = ui.selectedKeys + key
                                            }
                                            ui.selectionHint = ""
                                        },
                                        onSelectionChange = { ui.selectedKeys = it },
                                        onSelectionHint = { ui.selectionHint = it },
                                        onCopyText = { text, body ->
                                            context
                                                .getSystemService(ClipboardManager::class.java)
                                                .setPrimaryClip(
                                                    ClipData.newPlainText(
                                                        if (body) clipboardBodyLabel
                                                        else clipboardTitleLabel,
                                                        text,
                                                    )
                                                )
                                        },
                                    )

                                else ->
                                    BeaconSettingsTransition(settingsTransition, ui.settingsNavigatingBack || settingsMotion.targetState == ui.settingsBackTarget) {
                                        displayedSettingsScreen ->
                                        SettingsPage(
                                            displayedSettingsScreen,
                                            settingsScrollStates.getValue(displayedSettingsScreen),
                                            listModifier,
                                            listPadding,
                                            runtimeState,
                                            actions,
                                            ui.connectionForm,
                                            connectionDirty,
                                            notificationPermission,
                                            notificationHint,
                                            ui.uiStyle,
                                            ui.themeMode,
                                            onNavigate = { ui.navigateSettings(it) },
                                            receivePlan = ui.receivePlan,
                                            receptionForm = ui.receptionForm,
                                            onReceivePlanChange = { actions.receivePlan(it) },
                                            onShowReceiveModes = { ui.receiveModesOpen = true },
                                            onUiStyleChange = {
                                                ui.uiStyle = it
                                            },
                                            onThemeModeChange = {
                                                ui.themeMode = it
                                            },
                                            onOperationDetails = { ui.operationDetails = it },
                                            onPreviewReport = {
                                                ui.supportPreview =
                                                    viewModel.supportReport(
                                                        context
                                                            .getSystemService(
                                                                NotificationManager::class.java
                                                            )
                                                            .areNotificationsEnabled()
                                                    )
                                                ui.reportCopied = false
                                            },
                                            onRequestNotificationPermission =
                                                onRequestNotificationPermission,
                                            onNotificationSettingsUnavailable =
                                                onNotificationSettingsUnavailable,
                                        )
                                    }
                            }
                        }
                }
            }
        }
    }
}
