@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.feature.messages

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.MessageAction
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.MessageReadFilter
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.domain.policy.MessagePresentation
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.ActionFlow
import io.github.beacon.app.ui.components.BeaconEmptyState
import io.github.beacon.app.ui.components.BeaconMotion
import io.github.beacon.app.ui.components.BeaconPullToRefresh
import io.github.beacon.app.ui.components.BeaconSearchBox
import io.github.beacon.app.ui.components.BeaconSummaryPill
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconTextButton
import io.github.beacon.app.ui.components.BeaconUiMetrics
import io.github.beacon.app.ui.components.MessageItem
import io.github.beacon.app.ui.icons.BeaconIcons
import kotlinx.coroutines.delay

/**
 * Tag for the message list itself. Tests used to find it by the surrounding copy, which meant any
 * wording change silently broke them; the tag is layout-independent.
 */
const val MESSAGE_LIST_TAG = "message-list"

@Composable
fun MessagesPage(
    scroll: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
    padding: PaddingValues,
    runtimeState: RuntimeState,
    actions: RuntimeActions,
    search: String,
    readFilter: MessageReadFilter,
    selectionMode: Boolean,
    selectedKeys: List<MessageKey>,
    selectionHint: String,
    scrollModifier: Modifier = Modifier,
    sharedTitle: String? = null,
    hideSharedTitle: Boolean = false,
    searchOpen: Boolean = false,
    onSearchOpenChange: (Boolean) -> Unit = {},
    onSearch: (String) -> Unit,
    onApplyFilter: (String, MessageReadFilter) -> Unit,
    onConnectionSettings: () -> Unit,
    onEnterSelection: (MessageKey) -> Unit,
    onSelectionChange: (List<MessageKey>) -> Unit,
    onSelectionHint: (String) -> Unit,
    onRequestDelete: (List<MessageKey>) -> Unit,
    onCopyText: (String, Boolean) -> Unit,
) {
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    // Retain the previous date/zone refresh on parent updates without invalidating the list
    // for every runtime change. Both values are immutable and the pair retains its identity.
    val calendar = remember(zone, today) { zone to today }
    MessagesContent(
        scroll, modifier, padding, rememberMessageListState(runtimeState), actions,
        search, readFilter, selectionMode, selectedKeys, selectionHint, scrollModifier,
        sharedTitle, hideSharedTitle, searchOpen, onSearchOpenChange, onSearch, onApplyFilter,
        onConnectionSettings,
        onEnterSelection, onSelectionChange, onSelectionHint, onRequestDelete, onCopyText, calendar,
    )
}

// The remembered projection keeps the same identity for heartbeat/diagnostic updates, so
// Compose can skip this entire subtree even while the surrounding toolbar updates.
@Composable
private fun MessagesContent(
    scroll: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
    padding: PaddingValues,
    runtimeState: MessageListState,
    actions: RuntimeActions,
    search: String,
    readFilter: MessageReadFilter,
    selectionMode: Boolean,
    selectedKeys: List<MessageKey>,
    selectionHint: String,
    scrollModifier: Modifier,
    sharedTitle: String?,
    hideSharedTitle: Boolean,
    searchOpen: Boolean,
    onSearchOpenChange: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onApplyFilter: (String, MessageReadFilter) -> Unit,
    onConnectionSettings: () -> Unit,
    onEnterSelection: (MessageKey) -> Unit,
    onSelectionChange: (List<MessageKey>) -> Unit,
    onSelectionHint: (String) -> Unit,
    onRequestDelete: (List<MessageKey>) -> Unit,
    onCopyText: (String, Boolean) -> Unit,
    calendar: Pair<java.time.ZoneId, java.time.LocalDate>,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val zone = calendar.first
    val dates =
        remember(runtimeState.messages, zone) {
            runtimeState.messages.map { MessagePresentation.receivedDate(it, zone) }
        }
    val focus = LocalFocusManager.current
    val layoutDirection = LocalLayoutDirection.current
    // The shared title only folds once the list has actually scrolled.
    val titleCollapsed by remember { derivedStateOf { scroll.firstVisibleItemIndex > 0 } }
    val busy = runtimeState.messageBusy
    // The pinned filter row and the chips read the same applied query, so they never disagree.
    val applied = runtimeState.messageQuery
    val pageState = io.github.beacon.app.domain.model.AsyncPageState.resolve(
        loaded = runtimeState.messagesLoaded || runtimeState.messages.isNotEmpty(),
        loading = runtimeState.messageBusy || (!runtimeState.messagesLoaded && !runtimeState.messageLoadFailed),
        failed = runtimeState.messageLoadFailed,
        empty = runtimeState.messages.isEmpty(),
    )
    val awaitingMessages = pageState == io.github.beacon.app.domain.model.AsyncPageState.INITIAL_LOADING
    var showLoading by remember { mutableStateOf(false) }
    LaunchedEffect(awaitingMessages, busy, runtimeState.runtimeConnected) {
        showLoading = false
        if ((awaitingMessages && runtimeState.runtimeConnected) || busy) {
            delay(400)
            showLoading = true
        }
    }
    // `onSelection` runs outside a composable scope, so the hint is resolved here.
    val shareFailureHint = stringResource(R.string.message_share_failed)
    val maxSelectionHint = stringResource(R.string.msg_max_selection_hint)
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing, busy) {
        if (refreshing && !busy) {
            delay(200)
            refreshing = false
        }
    }
    val nearEnd by remember {
        derivedStateOf {
            scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
                it >= scroll.layoutInfo.totalItemsCount - 3
            } == true
        }
    }
    LaunchedEffect(
        nearEnd,
        runtimeState.nextMessageCursor,
        busy,
        runtimeState.messageLoadFailed,
        selectionMode,
    ) {
        if (
            nearEnd &&
                !busy &&
                !runtimeState.messageLoadFailed &&
                !selectionMode &&
                runtimeState.nextMessageCursor != null &&
                runtimeState.messages.isNotEmpty()
        )
            actions.nextMessages()
    }
    BackHandler(enabled = searchOpen) {
        focus.clearFocus()
        onSearchOpenChange(false)
    }
    LaunchedEffect(searchOpen) {
        if (!searchOpen) focus.clearFocus()
    }
    // Typing applies once the user pauses, so a query is not issued in the middle of a word.
    // Enter and the filter pills still apply immediately.
    LaunchedEffect(search, busy) {
        if (search != applied.search && !busy) {
            delay(220)
            onApplyFilter(search, readFilter)
        }
    }
    Column(modifier) {
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                    bottom = BeaconUiMetrics.Gap8,
                )
        ) {
            val submit = {
                if (!busy) {
                    onApplyFilter(search, readFilter)
                    focus.clearFocus()
                }
            }
            // Search is on demand: the bar's magnifier opens the field, back or the same
            // magnifier folds it away again, so the resting header keeps the title and filters.
            if (searchOpen) {
                val searchFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { searchFocus.requestFocus() }
                BeaconSearchBox(
                    search,
                    { if (it.length <= 256) onSearch(it) },
                    stringResource(R.string.msg_search_hint),
                    Modifier.focusRequester(searchFocus),
                    submit,
                )
            } else if (sharedTitle != null && !titleCollapsed && !hideSharedTitle) {
                BeaconText(
                    sharedTitle,
                    style = MaterialTheme.typography.titleLarge,
                    modifier =
                        Modifier.padding(
                            top = BeaconUiMetrics.Gap4,
                            bottom = BeaconUiMetrics.Gap8,
                        ),
                )
            }
            // Counts and active filters stay with the search instead of scrolling: they pin under
            // the search box with it, and the pull indicator then opens below this row.
            Column(
                Modifier.padding(top = BeaconUiMetrics.Gap8),
                verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap6),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8),
                ) {
                    BeaconSummaryPill(
                        stringResource(R.string.msg_stat_total, runtimeState.messageTotal),
                        BeaconIcons.Mail,
                        highlighted = applied.readFilter == MessageReadFilter.ALL,
                        enabled = !busy,
                        onClick = { onApplyFilter(applied.search, MessageReadFilter.ALL) },
                        modifier = Modifier.weight(1f),
                        compact = true,
                    )
                    BeaconSummaryPill(
                        stringResource(
                            R.string.msg_stat_unread,
                            runtimeState.messageUnread,
                        ),
                        BeaconIcons.Messages,
                        highlighted = applied.readFilter == MessageReadFilter.UNREAD,
                        enabled = !busy,
                        onClick = { onApplyFilter(applied.search, MessageReadFilter.UNREAD) },
                        modifier = Modifier.weight(1f),
                        compact = true,
                    )
                }
                if (
                    applied.readFilter != MessageReadFilter.ALL ||
                        applied.search.isNotBlank()
                )
                    ActionFlow {
                        if (applied.readFilter != MessageReadFilter.ALL)
                            InputChip(
                                shape = BeaconUiMetrics.PillShape,
                                selected = true,
                                enabled = !busy,
                                onClick = {
                                    onApplyFilter(
                                        applied.search,
                                        MessageReadFilter.ALL,
                                    )
                                },
                                label = {
                                    BeaconText(
                                        stringResource(
                                            if (
                                                applied.readFilter == MessageReadFilter.READ
                                            )
                                                R.string.msg_read_label
                                            else R.string.msg_unread_label
                                        )
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        BeaconIcons.Close,
                                        stringResource(R.string.msg_clear_filters_action),
                                        Modifier.size(BeaconUiMetrics.IconSmall),
                                    )
                                },
                            )
                        if (applied.search.isNotBlank())
                            InputChip(
                                shape = BeaconUiMetrics.PillShape,
                                selected = true,
                                enabled = !busy,
                                onClick = {
                                    onSearch("")
                                    onApplyFilter("", applied.readFilter)
                                },
                                label = {
                                    BeaconText(
                                        applied.search,
                                        maxLines = 1,
                                        overflow =
                                            androidx.compose.ui.text.style.TextOverflow
                                                .Ellipsis,
                                    )
                                },
                                leadingIcon = {
                                    Icon(BeaconIcons.Search, null, Modifier.size(BeaconUiMetrics.IconSmall))
                                },
                                trailingIcon = {
                                    Icon(
                                        BeaconIcons.Close,
                                        stringResource(R.string.msg_clear_filters_action),
                                        Modifier.size(BeaconUiMetrics.IconSmall),
                                    )
                                },
                            )
                    }
            }
        }
        BeaconPullToRefresh(
            refreshing,
            {
                if (!busy) {
                    refreshing = true
                    actions.readMessages(showStatus = false)
                }
            },
            Modifier.fillMaxWidth().weight(1f),
        ) {
            LazyColumn(
                state = scroll,
                modifier =
                    Modifier.fillMaxSize().then(scrollModifier).testTag(MESSAGE_LIST_TAG),
                contentPadding =
                    PaddingValues(
                        start = padding.calculateStartPadding(layoutDirection),
                        end = padding.calculateEndPadding(layoutDirection),
                        top = BeaconUiMetrics.Gap4,
                        bottom = padding.calculateBottomPadding(),
                    ),
                verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12),
            ) {
                if (selectionHint.isNotBlank())
                    item(key = "selection-hint", contentType = "hint") {
                        io.github.beacon.app.ui.components.SettingsNotice(selectionHint)
                    }
                if (runtimeState.messages.isEmpty())
                    item(key = "empty", contentType = "empty") {
                        // One key per visual state: loading, failure, and empty now fade into
                        // each other instead of swapping content inside a single item.
                        val emptyKey =
                            when {
                                awaitingMessages && showLoading -> "loading"
                                pageState ==
                                    io.github.beacon.app.domain.model.AsyncPageState
                                        .INITIAL_ERROR ||
                                    pageState ==
                                        io.github.beacon.app.domain.model.AsyncPageState
                                            .REFRESH_ERROR -> "failed"
                                awaitingMessages ||
                                    pageState ==
                                        io.github.beacon.app.domain.model.AsyncPageState.IDLE -> "idle"
                                else -> "empty"
                            }
                        Crossfade(
                            targetState = emptyKey,
                            animationSpec = tween(BeaconMotion.StandardMs),
                            label = "message-empty-state",
                        ) { key ->
                            when (key) {
                                "loading" ->
                                    BeaconEmptyState(
                                        BeaconIcons.Messages,
                                        stringResource(R.string.msg_loading),
                                        stringResource(R.string.msg_loading_local_list),
                                        loading = true,
                                    )

                                "failed" ->
                                    BeaconEmptyState(
                                        BeaconIcons.Warning,
                                        stringResource(R.string.ui_list_load_failed),
                                        stringResource(R.string.ui_list_load_failed_hint),
                                    ) {
                                        BeaconTextButton(onClick = { actions.readMessages() }) {
                                            BeaconText(stringResource(R.string.action_retry))
                                        }
                                    }

                                "idle" -> Unit

                                else -> {
                                    val filtered =
                                        search.isNotBlank() ||
                                            readFilter != MessageReadFilter.ALL ||
                                            runtimeState.messageQuery.serverId.isNotBlank()
                                    val unconfigured = runtimeState.configuredEndpoint.isBlank()
                                    BeaconEmptyState(
                                        BeaconIcons.Messages,
                                        stringResource(
                                            when {
                                                filtered -> R.string.msg_empty_no_match
                                                unconfigured -> R.string.msg_empty_unconfigured
                                                else -> R.string.msg_empty_no_messages
                                            }
                                        ),
                                        stringResource(
                                            when {
                                                filtered -> R.string.msg_empty_filter_hint
                                                unconfigured ->
                                                    R.string.msg_empty_unconfigured_hint
                                                else -> R.string.msg_empty_no_messages_hint
                                            }
                                        ),
                                    ) {
                                        BeaconTextButton(onClick = {
                                            when {
                                                filtered -> onApplyFilter("", MessageReadFilter.ALL)
                                                unconfigured -> onConnectionSettings()
                                                else -> actions.transport()
                                            }
                                        }) {
                                            BeaconText(
                                                stringResource(
                                                    when {
                                                        filtered -> R.string.msg_clear_filters_action
                                                        unconfigured ->
                                                            R.string.ui_v2_server_config_title
                                                        else -> R.string.action_sync_now
                                                    }
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                runtimeState.messages.forEachIndexed { index, message ->
                    val date = dates[index]
                    if (index == 0 || date != dates[index - 1]) {
                        item(key = "date.${message.server.length}:${message.server}${message.id}", contentType = "date") {
                            BeaconText(
                                when (date) {
                                    null -> stringResource(R.string.message_unknown_date)
                                    else -> date.toString()
                                },
                                Modifier.padding(start = BeaconUiMetrics.Gap4, top = BeaconUiMetrics.Gap4)
                                .semantics { heading() },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(key = MessageKey(message.server, message.id), contentType = "message") {
                        val key = MessageKey(message.server, message.id)
                        MessageItem(
                            message,
                            selectionMode,
                            key in selectedKeys,
                            // Arriving, read and selection changes settle into place instead of
                            // snapping, which is what made a synced batch look like a redraw.
                            modifier = Modifier.animateItem(),
                            onSelect = { checked ->
                                if (!checked) onSelectionChange(selectedKeys - key)
                                else if (selectedKeys.size < 100) {
                                    onSelectionChange((selectedKeys + key).distinct())
                                    onSelectionHint("")
                                } else onSelectionHint(maxSelectionHint)
                            },
                            onCopy = { body ->
                                if (body)
                                    actions.loadDetail(message) { full ->
                                        onCopyText(full.body, true)
                                    }
                                else onCopyText(message.title, false)
                            },
                            onDelete = { onRequestDelete(listOf(key)) },
                            busy = busy,
                            onOpen = { actions.loadDetail(message) },
                            onShare = {
                                actions.loadDetail(message) { full ->
                                    runCatching { shareMessage(context, full) }
                                        .onFailure { onSelectionHint(shareFailureHint) }
                                }
                            },
                            onRemind = { actions.remindMessage(key) },
                            onFilterGroup = {
                                val value = "group:${message.group}"
                                onSearch(value)
                                onApplyFilter(value, readFilter)
                            },
                            onFilterApplication = {
                                val value = "app:${message.applicationId}"
                                onSearch(value)
                                onApplyFilter(value, readFilter)
                            },
                            onLongPress = { onEnterSelection(key) },
                            onToggleRead = {
                                actions.manageMessages(
                                    if (message.isRead) MessageAction.MARK_UNREAD
                                    else MessageAction.MARK_READ,
                                    listOf(key),
                                    preservePage = true,
                                )
                            },
                        )
                    }
                }
                item(key = "history-loading", contentType = "loading") {
                    Box(
                        Modifier.fillMaxWidth().padding(BeaconUiMetrics.SectionGap),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (runtimeState.dataBusy && runtimeState.messages.isNotEmpty())
                            CircularProgressIndicator(Modifier.size(BeaconUiMetrics.IconMedium), strokeWidth = 2.dp)
                        else if (runtimeState.nextMessageCursor != null)
                            BeaconTextButton(enabled = !busy, onClick = actions::nextMessages) {
                                BeaconText(
                                    if (runtimeState.messageLoadFailed)
                                        stringResource(R.string.msg_history_retry)
                                    else stringResource(R.string.msg_history_more)
                                )
                            }
                        else if (runtimeState.messages.isNotEmpty())
                            BeaconText(
                                stringResource(R.string.msg_history_exhausted),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                }
            }
        }
    }
}