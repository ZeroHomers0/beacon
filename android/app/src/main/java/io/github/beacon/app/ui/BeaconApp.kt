@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.R
import io.github.beacon.app.data.remote.ReleaseClient
import io.github.beacon.app.domain.model.MessageAction
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.MessageReadFilter
import io.github.beacon.app.feature.messages.MessageDetailScreen
import io.github.beacon.app.feature.messages.MessagesPage
import io.github.beacon.app.feature.settings.ReceiveModeDialog
import io.github.beacon.app.feature.settings.SettingsPage
import io.github.beacon.app.feature.settings.UpdateCheckDialog
import io.github.beacon.app.feature.settings.UpdateCheckReport
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconActionIcon
import io.github.beacon.app.ui.components.BeaconAlertDialog
import io.github.beacon.app.ui.components.BeaconButton
import io.github.beacon.app.ui.components.BeaconConfirmDialog
import io.github.beacon.app.ui.components.BeaconDropdownMenu
import io.github.beacon.app.ui.components.BeaconDropdownMenuItem
import io.github.beacon.app.ui.components.BeaconIconButton
import io.github.beacon.app.ui.components.BeaconMessageTransition
import io.github.beacon.app.ui.components.BeaconOutlinedButton
import io.github.beacon.app.ui.components.BeaconPagerToolbar
import io.github.beacon.app.ui.components.BeaconScaffold
import io.github.beacon.app.ui.components.BeaconSelectableRow
import io.github.beacon.app.ui.components.BeaconSettingsTransition
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconTextButton
import io.github.beacon.app.ui.components.BeaconTopBar
import io.github.beacon.app.ui.components.BeaconUiMetrics
import io.github.beacon.app.ui.components.NotificationPermissionState
import io.github.beacon.app.ui.components.PredictiveBackReset
import io.github.beacon.app.ui.components.ReceiveModeButton
import io.github.beacon.app.ui.components.SettingsScreen
import io.github.beacon.app.ui.components.copyToClipboard
import io.github.beacon.app.ui.components.rememberBeaconBarBehavior
import io.github.beacon.app.ui.components.settingsGestureFraction
import io.github.beacon.app.ui.components.userOperationFeedback
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.BeaconTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val adminActivity =
        checkNotNull(
            androidx.activity.compose.LocalActivity.current as? androidx.activity.ComponentActivity
        )
    val adminModel =
        remember(adminActivity) {
            androidx.lifecycle
                .ViewModelProvider(adminActivity)[
                    io.github.beacon.app.feature.settings.ServerAdminViewModel::class.java]
        }
    val adminState by adminModel.state.collectAsStateWithLifecycle()
    val ui = viewModel.uiState
    val runtimeState by actions.state.collectAsStateWithLifecycle()
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
                // Runtime events refresh changes immediately; this only repairs missed hints.
                delay(60_000)
            }
        }
    }
    // One release check per entry to the foreground, and never two at once. It only decides whether
    // the settings entry shows its dot: the announcement itself waits for the user to ask.
    val updateState = ui.updateState
    LaunchedEffect(updateState, context) {
        updateState.attachPreferences(context.getSharedPreferences("update_notice", 0))
    }
    val checkForUpdate: suspend (Boolean) -> UpdateCheckReport = { manual ->
        updateState.check(manual) {
            withContext(Dispatchers.IO) { ReleaseClient.latest(BuildConfig.VERSION_NAME) }
        }
    }
    val updateScope = rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        // The saved server is re-probed on every return to the foreground, but the toolbar entry
        // keeps the last verdict while it runs, so a healthy server never flashes "checking".
        actions.autoCheckServer()
        actions.refreshPolling()
        updateScope.launch { checkForUpdate(false) }
    }

    BeaconTheme(ui.uiStyle, ui.themeMode) {
        val pager = rememberPagerState(initialPage = ui.page) { 2 }
        val pageNavigationMotion =
            spring<Float>(
                stiffness = 322.2f,
                dampingRatio = 32.31f / (2f * kotlin.math.sqrt(322.2f)),
                visibilityThreshold = 0.5f,
            )
        LaunchedEffect(ui.page) {
            pager.animateScrollToPage(ui.page, animationSpec = pageNavigationMotion)
        }
        LaunchedEffect(runtimeState.configuredEndpoint, runtimeState.connectionSummaryLoaded) {
            if (!ui.connectionForm.connectionEdited)
                ui.connectionForm.endpoint = runtimeState.configuredEndpoint
        }
        val connectionDirty = ui.connectionDirty(runtimeState)
        val snackbar = remember { SnackbarHostState() }
        // Clipboard labels are consumed inside onClick lambdas, so they resolve outside those
        // scopes.
        val clipboardBodyLabel = stringResource(R.string.ui_widget_body_label)
        val clipboardTitleLabel = stringResource(R.string.ui_widget_title_label)
        val updateLatestCopy = stringResource(R.string.settings_update_latest)
        val updateFailedCopy = stringResource(R.string.settings_update_failed)
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
        // Only a check the user asked for reports back: the background one stays silent, and its
        // dot is the whole message.
        val checkUpdateNow: () -> Unit = {
            updateScope.launch {
                when (checkForUpdate(true)) {
                    UpdateCheckReport.LATEST ->
                        snackbar.showSnackbar(updateLatestCopy, duration = SnackbarDuration.Short)
                    UpdateCheckReport.FAILED ->
                        snackbar.showSnackbar(updateFailedCopy, duration = SnackbarDuration.Short)
                    // AVAILABLE already opened the dialog; SILENT was never requested by the user.
                    else -> Unit
                }
            }
        }
        ui.updateState.release?.let { release ->
            if (ui.updateState.dialogOpen)
                UpdateCheckDialog(
                    state = ui.updateState,
                    release = release,
                    scope = updateScope,
                    onDismiss = { ui.updateState.closeDialog() },
                    onFeedback = { message ->
                        updateScope.launch {
                            snackbar.showSnackbar(message, duration = SnackbarDuration.Short)
                        }
                    },
                )
        }
        // The reception page keeps one draft for the whole page, so it is seeded from both halves
        // of the saved state. A draft that already matches the runtime is re-seeded too, which is
        // how a completed save clears it; a half-finished save stays editable for a retry.
        LaunchedEffect(
            runtimeState.receivePlan,
            runtimeState.pollingState.config,
            runtimeState.receivePlanLoaded,
        ) {
            if (
                !ui.receptionForm.edited ||
                    !ui.receptionForm.differsFrom(
                        runtimeState.pollingState.config,
                        runtimeState.receivePlan,
                    )
            )
                ui.receptionForm.reset(runtimeState.pollingState.config, runtimeState.receivePlan)
        }
        SideEffect {
            ui.savingSettings =
                ui.notificationForm.rulesSaving ||
                    (ui.settingsScreen == SettingsScreen.RECEPTION && runtimeState.transportBusy)
            ui.shouldConfirmExit = { destination ->
                val notificationPages =
                    setOf(SettingsScreen.NOTIFICATION_RULES, SettingsScreen.QUIET_HOURS)
                (ui.settingsScreen == SettingsScreen.RECEPTION &&
                    destination != SettingsScreen.RECEPTION &&
                    ui.receptionForm.edited &&
                    ui.receptionForm.differsFrom(
                        runtimeState.pollingState.config,
                        runtimeState.receivePlan,
                    )) ||
                    (ui.settingsScreen in notificationPages &&
                        destination !in notificationPages &&
                        (ui.quietHoursForm.loaded && ui.quietHoursForm.changed ||
                            ui.notificationForm.changed))
            }
            ui.discardDrafts = {
                ui.receptionForm.reset(runtimeState.pollingState.config, runtimeState.receivePlan)
                ui.quietHoursForm.reset(ui.quietHoursForm.saved)
                ui.notificationForm.reset(ui.notificationForm.saved)
            }
        }
        if (ui.pendingNavigation != null)
            BeaconAlertDialog(
                onDismissRequest = { if (!ui.savingSettings) ui.pendingNavigation = null },
                title = { BeaconText(stringResource(R.string.settings_unsaved_title)) },
                text = { BeaconText(stringResource(R.string.settings_unsaved_hint)) },
                confirmButton = {
                    BeaconButton(
                        onClick = { ui.pendingNavigation = null },
                        enabled = !ui.savingSettings,
                    ) {
                        BeaconText(stringResource(R.string.settings_continue_editing))
                    }
                },
                dismissButton = {
                    BeaconOutlinedButton(
                        onClick = ui::discardAndContinue,
                        enabled = !ui.savingSettings,
                    ) {
                        BeaconText(stringResource(R.string.settings_discard_changes))
                    }
                },
            )
        if (ui.receiveModesOpen)
            ReceiveModeDialog(
                ui.receivePlan.mode,
                onSelect = selectMode@{ mode ->
                        if (
                            !runtimeState.receivePlanLoaded ||
                                !runtimeState.runtimeConnected ||
                                runtimeState.messageBusy ||
                                ui.savingSettings
                        )
                            return@selectMode
                        if (mode != ui.receivePlan.mode) {
                            val changeMode = {
                                ui.receptionForm.reset(
                                    runtimeState.pollingState.config,
                                    runtimeState.receivePlan.copy(mode = mode),
                                )
                                actions.receivePlan(ui.receivePlan.copy(mode = mode))
                            }
                            if (
                                ui.receptionForm.edited &&
                                    ui.receptionForm.differsFrom(
                                        runtimeState.pollingState.config,
                                        runtimeState.receivePlan,
                                    )
                            )
                                ui.pendingNavigation = changeMode
                            else changeMode()
                        }
                        ui.receiveModesOpen = false
                    },
                onDismiss = { ui.receiveModesOpen = false },
            )
        val messageScroll = rememberLazyListState()
        androidx.lifecycle.compose.LifecycleEventEffect(
            androidx.lifecycle.Lifecycle.Event.ON_STOP
        ) {
            val item =
                messageScroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key is MessageKey }
            ui.readingKey = item?.key as? MessageKey
            ui.readingOffset = item?.let { (-it.offset).coerceAtLeast(0) } ?: 0
        }
        LaunchedEffect(runtimeState.messages, runtimeState.messageBusy, ui.restoreReadingPosition) {
            if (
                ui.restoreReadingPosition &&
                    !runtimeState.messageBusy &&
                    runtimeState.messages.isNotEmpty()
            ) {
                val index =
                    runtimeState.messages.indexOfFirst {
                        MessageKey(it.server, it.id) == ui.readingKey
                    }
                val zone = java.time.ZoneId.systemDefault()
                val headers =
                    if (index < 0) 0
                    else
                        (0..index).count { position ->
                            position == 0 ||
                                io.github.beacon.app.domain.policy.MessagePresentation.receivedDate(
                                    runtimeState.messages[position],
                                    zone,
                                ) !=
                                    io.github.beacon.app.domain.policy.MessagePresentation
                                        .receivedDate(runtimeState.messages[position - 1], zone)
                        }
                messageScroll.scrollToItem(
                    if (index < 0) 0 else 1 + index + headers,
                    if (index < 0) 0 else ui.readingOffset,
                )
                ui.restoreReadingPosition = false
            }
        }
        val settingsScrollStates = SettingsScreen.entries.associateWith { rememberLazyListState() }
        val uiScope = rememberCoroutineScope()
        val focus = LocalFocusManager.current
        // Clearing focus needs the Compose owner, so wrap the state-holder helper.
        val navigate: (Int) -> Unit = { index ->
            focus.clearFocus()
            ui.navigate(actions, runtimeState, index)
        }
        val settingsMotion = remember { SeekableTransitionState(ui.settingsScreen) }
        val settingsBackReset = remember { PredictiveBackReset() }
        val settingsTransition = rememberTransition(settingsMotion, label = "Settings navigation")
        LaunchedEffect(ui.settingsScreen) {
            settingsBackReset.stop()
            settingsMotion.animateTo(ui.settingsScreen)
        }
        val detailMotion = remember { SeekableTransitionState(runtimeState.selectedMessage) }
        val detailBackReset = remember { PredictiveBackReset() }
        val detailTransition = rememberTransition(detailMotion, label = "Message navigation")
        LaunchedEffect(runtimeState.selectedMessage) {
            detailBackReset.stop()
            detailMotion.animateTo(runtimeState.selectedMessage)
        }
        val predictiveDetailBack = runtimeState.selectedMessage != null && !runtimeState.messageBusy
        PredictiveBackHandler(enabled = predictiveDetailBack) { events ->
            detailBackReset.stop()
            val original = runtimeState.selectedMessage
            try {
                detailMotion.seekTo(0f, null)
                events.collect { event ->
                    detailMotion.seekTo(settingsGestureFraction(event.progress), null)
                }
                ui.closeDetail(actions)
            } catch (_: kotlinx.coroutines.CancellationException) {
                detailBackReset.launch(uiScope, detailMotion, original)
            }
        }
        val predictiveSettingsBack =
            ui.page == 1 &&
                ui.settingsScreen != SettingsScreen.HOME &&
                runtimeState.selectedMessage == null &&
                !runtimeState.messageBusy
        PredictiveBackHandler(enabled = predictiveSettingsBack) { events ->
            settingsBackReset.stop()
            val original = ui.settingsScreen
            try {
                settingsMotion.seekTo(0f, ui.settingsBackTarget)
                events.collect { event ->
                    settingsMotion.seekTo(
                        settingsGestureFraction(event.progress),
                        ui.settingsBackTarget,
                    )
                }
                navigate(1)
                if (ui.pendingNavigation != null || ui.savingSettings)
                    settingsBackReset.launch(uiScope, settingsMotion, original)
            } catch (_: kotlinx.coroutines.CancellationException) {
                settingsBackReset.launch(uiScope, settingsMotion, original)
            }
        }
        val predictivePageBack =
            ui.page == 1 &&
                ui.settingsScreen == SettingsScreen.HOME &&
                runtimeState.selectedMessage == null &&
                !runtimeState.messageBusy
        PredictiveBackHandler(enabled = predictivePageBack) { events ->
            focus.clearFocus()
            try {
                events.collect { event ->
                    // Pager offsets stay in [-.5, .5]; switch the anchor halfway through.
                    val position = 1f - event.progress.coerceIn(0f, 1f)
                    val anchor = if (position < .5f) 0 else 1
                    pager.scrollToPage(anchor, position - anchor)
                }
                navigate(0)
            } catch (_: kotlinx.coroutines.CancellationException) {
                uiScope.launch {
                    pager.animateScrollToPage(ui.page, animationSpec = pageNavigationMotion)
                }
            }
        }
        BackHandler(
            enabled =
                !predictiveSettingsBack &&
                    !predictiveDetailBack &&
                    !predictivePageBack &&
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
            if (runtimeState.messagePageIndex == 0 && !ui.restoreReadingPosition)
                messageScroll.scrollToItem(0)
        }
        LaunchedEffect(ui.search, runtimeState.messageBusy) {
            delay(350)
            if (!runtimeState.messageBusy && ui.search != runtimeState.messageQuery.search)
                ui.applyFilter(actions, ui.search, ui.readFilter)
        }
        ui.pendingDelete?.let { keys ->
            BeaconConfirmDialog(
                title = stringResource(R.string.msg_delete_confirm_title, keys.size),
                description =
                    if (keys.size == 1) stringResource(R.string.ui_delete_undo_hint)
                    else stringResource(R.string.ui_delete_multi_warning_hint),
                confirmText = stringResource(R.string.ui_confirm_delete_action),
                danger = false,
                enabled = !runtimeState.messageBusy,
                onDismiss = { ui.pendingDelete = null },
                onConfirm = {
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
            windowHeightDp > 0.dp && windowHeightDp < SHARED_TITLE_MAX_HEIGHT && !ui.selectionMode
        // A short window folds the title into the search row; opening the IME preserves this
        // layout.
        val sharedTitleLabel = if (sharedTitleBar) stringResource(ui.pages[0]) else null
        val barBehavior = rememberBeaconBarBehavior()
        val compactBar =
            (sharedTitleBar && ui.page == 0) ||
                (ui.page == 0 && ui.selectionMode) ||
                detail != null ||
                (ui.page == 1 && ui.settingsScreen != SettingsScreen.HOME)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                BeaconScaffold(
                    snackbar = snackbar,
                    bottomBar = {},
                    topBar = {
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
                                                BeaconActionIcon(
                                                    BeaconIcons.Sync,
                                                    stringResource(R.string.admin_refresh),
                                                    adminState.loggedIn && !adminState.busy,
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
                                                BeaconActionIcon(
                                                    BeaconIcons.Sync,
                                                    stringResource(R.string.admin_refresh),
                                                    adminState.loggedIn && !adminState.busy,
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
                                                BeaconActionIcon(
                                                    BeaconIcons.Save,
                                                    stringResource(R.string.action_save),
                                                    runtimeState.runtimeConnected &&
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
                                                BeaconActionIcon(
                                                    BeaconIcons.Save,
                                                    stringResource(R.string.action_save),
                                                    runtimeState.runtimeConnected &&
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
                    val edge = BeaconUiMetrics.PageInset
                    val listPadding =
                        PaddingValues(start = edge, end = edge, top = 12.dp, bottom = 24.dp)
                    // Scaffold follows the animated header height; animating body size again
                    // would change the shared transition duration during a back gesture.
                    BeaconMessageTransition(
                        detailTransition,
                        Modifier.fillMaxSize(),
                        animateSize = false,
                    ) { detail ->
                        if (detail != null)
                            MessageDetailScreen(
                                detail,
                                listModifier,
                                onCopy = { body ->
                                    copyToClipboard(
                                        context,
                                        if (body) clipboardBodyLabel else clipboardTitleLabel,
                                        if (body) detail.body else detail.title,
                                    )
                                },
                                onDelete = {
                                    ui.pendingDelete = listOf(MessageKey(detail.server, detail.id))
                                },
                                busy = busy,
                                onShare = {
                                    io.github.beacon.app.feature.messages.shareMessage(
                                        context,
                                        detail,
                                    )
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
                                                copyToClipboard(
                                                    context,
                                                    if (body) clipboardBodyLabel
                                                    else clipboardTitleLabel,
                                                    text,
                                                )
                                            },
                                        )

                                    else ->
                                        BeaconSettingsTransition(
                                            settingsTransition,
                                            ui.settingsNavigatingBack ||
                                                settingsMotion.targetState == ui.settingsBackTarget,
                                            animateSize = false,
                                        ) { displayedSettingsScreen ->
                                            SettingsPage(
                                                displayedSettingsScreen,
                                                settingsScrollStates.getValue(
                                                    displayedSettingsScreen
                                                ),
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
                                                onShowReceiveModes = {
                                                    if (
                                                        runtimeState.runtimeConnected &&
                                                            runtimeState.receivePlanLoaded &&
                                                            !busy
                                                    )
                                                        ui.receiveModesOpen = true
                                                },
                                                onUiStyleChange = {
                                                    ui.uiStyle = it
                                                },
                                                onThemeModeChange = {
                                                    ui.themeMode = it
                                                },
                                                onOperationDetails = { ui.operationDetails = it },
                                                onFeedback = { message ->
                                                    uiScope.launch {
                                                        snackbar.currentSnackbarData?.dismiss()
                                                        snackbar.showSnackbar(
                                                            message,
                                                            duration = SnackbarDuration.Short,
                                                        )
                                                    }
                                                },
                                                onRequestNotificationPermission =
                                                    onRequestNotificationPermission,
                                                onNotificationSettingsUnavailable =
                                                    onNotificationSettingsUnavailable,
                                                updateState = ui.updateState,
                                                onCheckUpdate = checkUpdateNow,
                                                quietHoursForm = ui.quietHoursForm,
                                                notificationForm = ui.notificationForm,
                                            )
                                        }
                                }
                            }
                    }
                }
            }
        }
    }
}
