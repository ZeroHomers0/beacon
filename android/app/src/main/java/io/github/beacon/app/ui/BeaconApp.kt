@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.ui

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.feature.settings.UpdateCheckDialog
import io.github.beacon.app.feature.settings.UpdateCheckReport
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconScaffold
import io.github.beacon.app.ui.components.NotificationPermissionState
import io.github.beacon.app.ui.components.PredictiveBackReset
import io.github.beacon.app.ui.components.SettingsScreen
import io.github.beacon.app.ui.components.rememberBeaconBarBehavior
import io.github.beacon.app.ui.components.showFeedback
import io.github.beacon.app.ui.components.userOperationFeedback
import io.github.beacon.app.ui.theme.BeaconTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    LaunchedEffect(adminModel) { adminModel.restoreSession(adminActivity) }
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
        // Only a check the user asked for reports back: the background one stays silent, and its
        // dot is the whole message.
        val checkUpdateNow: () -> Unit = {
            updateScope.launch {
                when (checkForUpdate(true)) {
                    UpdateCheckReport.LATEST ->
                        snackbar.showFeedback(updateLatestCopy)
                    UpdateCheckReport.FAILED ->
                        snackbar.showFeedback(updateFailedCopy)
                    // AVAILABLE already opened the dialog; SILENT was never requested by the user.
                    else -> Unit
                }
            }
        }
        ui.updateState.release?.let { release ->
            if (ui.updateState.dialogOpen)
                UpdateCheckDialog(
                    release = release,
                    onDismiss = { ui.updateState.closeDialog() },
                    onFeedback = { message ->
                        updateScope.launch {
                            snackbar.showFeedback(message)
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
        BeaconDialogHost(
            ui = ui,
            runtimeState = runtimeState,
            actions = actions,
        )
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
        BeaconBackHandlers(
            ui = ui,
            runtimeState = runtimeState,
            actions = actions,
            pager = pager,
            pageNavigationMotion = pageNavigationMotion,
            detailMotion = detailMotion,
            settingsMotion = settingsMotion,
            settingsBackReset = settingsBackReset,
            detailBackReset = detailBackReset,
            navigate = navigate,
            uiScope = uiScope,
            focus = focus,
        )
        LaunchedEffect(
            runtimeState.messagePageIndex == 0,
            runtimeState.messageQuery.serverId,
            runtimeState.messageQuery.search,
            runtimeState.messageQuery.readFilter,
        ) {
            if (runtimeState.messagePageIndex == 0 && !ui.restoreReadingPosition)
                messageScroll.scrollToItem(0)
        }
        // Debounced typing. Pressing enter calls applyFilter directly, so this must not also fire
        // for the same text: searchPending() compares against the last submitted text instead of
        // the async messageQuery echo, which lags behind and used to double-submit.
        LaunchedEffect(ui.search, runtimeState.messageBusy) {
            delay(350)
            if (!runtimeState.messageBusy && ui.searchPending())
                ui.applyFilter(actions, ui.search, ui.readFilter)
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
                val display = launch {
                    snackbar.showFeedback(feedback, duration = SnackbarDuration.Indefinite)
                }
                kotlinx.coroutines.delay(feedbackTimeout)
                snackbar.currentSnackbarData?.dismiss()
                display.join()
            }
        }
        // The message page keeps its title out of a band of its own: the compact bar carries the
        // actions, and the page name joins it only once the list starts scrolling, so the first
        // screen is header-light and the title still answers "where am I" while reading.
        val compactMessageBar = ui.page == 0 && !ui.selectionMode
        val messageTitleVisible = remember { mutableStateOf(false) }
        // Leaving the page drops the flag: the settings bar must never inherit it.
        LaunchedEffect(ui.page) { if (ui.page != 0) messageTitleVisible.value = false }
        val barBehavior = rememberBeaconBarBehavior()
        val compactBar =
            ui.page == 0 ||
                detail != null ||
                (ui.page == 1 && ui.settingsScreen != SettingsScreen.HOME)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                BeaconScaffold(
                    snackbar = snackbar,
                    bottomBar = {},
                    topBar = {
                        BeaconTopBarArea(
                            ui = ui,
                            runtimeState = runtimeState,
                            actions = actions,
                            adminState = adminState,
                            adminModel = adminModel,
                            updateState = updateState,
                            detailTransition = detailTransition,
                            settingsTransition = settingsTransition,
                            settingsMotion = settingsMotion,
                            pager = pager,
                            barBehavior = barBehavior,
                            compactMessageBar = compactMessageBar,
                            titleVisible = messageTitleVisible.value,
                            navigate = navigate,
                        )
                    },
                ) { insets ->
                    BeaconContentArea(
                        ui = ui,
                        runtimeState = runtimeState,
                        actions = actions,
                        pager = pager,
                        detailTransition = detailTransition,
                        settingsTransition = settingsTransition,
                        settingsMotion = settingsMotion,
                        messageScroll = messageScroll,
                        settingsScrollStates = settingsScrollStates,
                        barBehavior = barBehavior,
                        compactBar = compactBar,
                        onMessageListScrolled = { messageTitleVisible.value = it },
                        busy = busy,
                        snackbar = snackbar,
                        uiScope = uiScope,
                        insets = insets,
                        connectionDirty = connectionDirty,
                        notificationPermission = notificationPermission,
                        notificationHint = notificationHint,
                        checkUpdateNow = checkUpdateNow,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onNotificationSettingsUnavailable = onNotificationSettingsUnavailable,
                    )
                }
            }
        }
    }
}
