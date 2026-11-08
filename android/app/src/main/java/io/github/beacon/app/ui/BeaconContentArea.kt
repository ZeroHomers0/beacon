package io.github.beacon.app.ui

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageAction
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.feature.messages.MessageDetailScreen
import io.github.beacon.app.feature.messages.MessagesPage
import io.github.beacon.app.feature.messages.shareMessage
import io.github.beacon.app.feature.settings.SettingsPage
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconBarBehavior
import io.github.beacon.app.ui.components.NotificationPermissionState
import io.github.beacon.app.ui.components.BeaconMessageTransition
import io.github.beacon.app.ui.components.BeaconSettingsTransition
import io.github.beacon.app.ui.components.BeaconUiMetrics
import io.github.beacon.app.ui.components.SettingsScreen
import io.github.beacon.app.ui.components.copyToClipboard
import io.github.beacon.app.ui.components.showFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Everything rendered inside the scaffold: the message detail, or the pager holding the message
 * list and the settings stack.
 *
 * Split out of [BeaconApp] so the shell above it stays readable: the shell owns the pager, the
 * shared feedback and navigation state, while this file owns each page and its top bar. List padding and
 * the nested-scroll wiring live here because the two pages consume them, not the shell.
 */
@Composable
internal fun BeaconContentArea(
    ui: BeaconUiState,
    runtimeState: RuntimeState,
    actions: RuntimeActions,
    pager: PagerState,
    detailTransition: Transition<IncomingMessage?>,
    settingsTransition: Transition<SettingsScreen>,
    settingsMotion: SeekableTransitionState<SettingsScreen>,
    messageScroll: LazyListState,
    statesScroll: LazyListState,
    settingsScrollStates: Map<SettingsScreen, LazyListState>,
    settingsBarBehaviors: Map<SettingsScreen, BeaconBarBehavior>,
    pageTopBar: @Composable (Int, SettingsScreen?) -> Unit,
    detailTopBar: @Composable (IncomingMessage, @Composable () -> Unit) -> Unit,
    pageBarBehaviors: Map<Int, BeaconBarBehavior>,
    sharedTitleLabel: String?,
    busy: Boolean,
    snackbar: SnackbarHostState,
    uiScope: CoroutineScope,
    connectionDirty: Boolean,
    notificationPermission: NotificationPermissionState,
    notificationHint: Int?,
    checkUpdateNow: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onNotificationSettingsUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardBodyLabel = stringResource(R.string.ui_widget_body_label)
    val clipboardTitleLabel = stringResource(R.string.ui_widget_title_label)
val edge = BeaconUiMetrics.PageInset
val listPadding =
    PaddingValues(start = edge, end = edge, top = BeaconUiMetrics.PageTopInset, bottom = BeaconUiMetrics.PageBottomInset)
// MIUIX lists scroll beneath the transparent title bar. Keep the initial item position by
// moving the top inset into scrollable content padding rather than shrinking the viewport.
val underTopBar = ui.uiStyle == io.github.beacon.app.domain.model.BeaconUiStyle.MIUIX
val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
// Page-owned scaffolds keep title and content geometry stable while the pager moves horizontally.
BeaconMessageTransition(
    detailTransition,
    Modifier.fillMaxSize(),
    animateSize = false,
) { detail ->
    if (detail != null) {
        MessageDetailScreen(
            detail,
            Modifier.fillMaxSize(),
            topBar = { menu -> detailTopBar(detail, menu) },
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
    } else
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
            beyondViewportPageCount = 1,
            overscrollEffect = null,
        ) { visiblePage ->
            when (visiblePage) {
                2 -> io.github.beacon.app.ui.components.BeaconScaffold(
                    topBar = { pageTopBar(2, null) }, bottomBar = {},
                ) { pageInsets ->
                    io.github.beacon.app.feature.messages.StatesPage(
                        Modifier.fillMaxSize().padding(pageInsets)
                            .nestedScroll(pageBarBehaviors.getValue(2).connection(ui.uiStyle)),
                        busy = runtimeState.messageBusy || runtimeState.transportBusy,
                        connected = runtimeState.runtimeConnected, showHidden = ui.statesShowHidden,
                        onShowHidden = { ui.statesShowHidden = it }, onRefresh = { actions.transport() }, scroll = statesScroll)
                }
                0 -> io.github.beacon.app.ui.components.BeaconScaffold(
                    topBar = { pageTopBar(0, null) }, bottomBar = {},
                ) { pageInsets ->
                    val messageModifier = if (underTopBar) Modifier.fillMaxSize().padding(
                        start = pageInsets.calculateStartPadding(direction), end = pageInsets.calculateEndPadding(direction),
                        bottom = pageInsets.calculateBottomPadding()) else Modifier.fillMaxSize().padding(pageInsets)
                    MessagesPage(
                        messageScroll,
                        messageModifier,
                        listPadding,
                        runtimeState,
                        actions,
                        ui.search,
                        ui.readFilter,
                        ui.selectionMode,
                        ui.selectedKeys,
                        ui.selectionHint,
                        scrollModifier = Modifier.nestedScroll(pageBarBehaviors.getValue(0).connection(ui.uiStyle)),
                        sharedTitle = sharedTitleLabel,
                        topInset = if (underTopBar) pageInsets.calculateTopPadding() else 0.dp,
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
                        onRequestDelete = { ui.pendingDelete = it },
                        onCopyText = { text, body ->
                            copyToClipboard(
                                context,
                                if (body) clipboardBodyLabel
                                else clipboardTitleLabel,
                                text,
                            )
                        },
                    )
                }

                else -> {
                    val renderSettings: @Composable (SettingsScreen, PaddingValues) -> Unit = { displayedSettingsScreen, routeInsets ->
                        val routeModifier = if(underTopBar) Modifier.fillMaxSize().padding(
                            start=routeInsets.calculateStartPadding(direction), end=routeInsets.calculateEndPadding(direction),
                            bottom=routeInsets.calculateBottomPadding()) else Modifier.fillMaxSize().padding(routeInsets)
                        val routePadding = if(underTopBar) PaddingValues(start=edge,end=edge,
                            top=routeInsets.calculateTopPadding()+BeaconUiMetrics.PageTopInset,
                            bottom=BeaconUiMetrics.PageBottomInset) else listPadding
                        BeaconSettingsPageFrame(displayedSettingsScreen,
                            routeModifier.nestedScroll(settingsBarBehaviors.getValue(displayedSettingsScreen).connection(ui.uiStyle)), routePadding) { pageModifier, pagePadding ->
                        SettingsPage(
                            displayedSettingsScreen,
                            settingsScrollStates.getValue(
                                displayedSettingsScreen
                            ),
                            pageModifier,
                            pagePadding,
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
                            onSelectReceiveMode = { mode -> ui.selectReceiveMode(runtimeState, actions, mode) },
                            onUiStyleChange = {
                                ui.uiStyle = it
                            },
                            onThemeModeChange = {
                                ui.themeMode = it
                            },
                            onOperationDetails = { ui.operationDetails = it },
                            onFeedback = { message ->
                                uiScope.launch { snackbar.showFeedback(message) }
                            },
                            onRequestNotificationPermission =
                                onRequestNotificationPermission,
                            onNotificationSettingsUnavailable =
                                onNotificationSettingsUnavailable,
                            updateState = ui.updateState,
                            onCheckUpdate = checkUpdateNow,
                            quietHoursForm = ui.quietHoursForm,
                            notificationForm = ui.notificationForm,
                            storageSettings = ui.storageSettings,
                        )
                    }
                    }
                    val renderRoute: @Composable (SettingsScreen) -> Unit = { screen ->
                        io.github.beacon.app.ui.components.BeaconScaffold(
                            topBar = { pageTopBar(1, screen) }, bottomBar = {},
                        ) { routeInsets -> renderSettings(screen, routeInsets) }
                    }
                    if (underTopBar) BeaconSettingsNavHost(ui, renderRoute)
                    else BeaconSettingsTransition(settingsTransition,
                        ui.settingsNavigatingBack || settingsMotion.targetState == ui.settingsBackTarget,
                        animateSize = false, content = renderRoute)
                }
            }
        }
}
}
