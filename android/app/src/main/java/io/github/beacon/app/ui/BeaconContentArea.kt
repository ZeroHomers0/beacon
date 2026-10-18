package io.github.beacon.app.ui

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
 * top bar and the navigation transitions, while this file owns the body. The list padding and
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
    settingsScrollStates: Map<SettingsScreen, LazyListState>,
    barBehavior: BeaconBarBehavior,
    compactBar: Boolean,
    onMessageListScrolled: (Boolean) -> Unit,
    busy: Boolean,
    snackbar: SnackbarHostState,
    uiScope: CoroutineScope,
    insets: PaddingValues,
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
                        onListScrolled = onMessageListScrolled,
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
                        )
                    }
            }
        }
}
}

