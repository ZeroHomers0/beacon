@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.beacon.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.domain.model.ReceiveMode
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.LocalBeaconUiStyle

fun receiveModeTitle(mode: ReceiveMode): Int =
    when (mode) {
        ReceiveMode.POWER_SAVING -> R.string.ui_power_saving_mode
        ReceiveMode.REALTIME -> R.string.receive_mode_realtime
        ReceiveMode.RULES -> R.string.receive_mode_rules
    }

fun receiveModeIcon(mode: ReceiveMode) =
    when (mode) {
        ReceiveMode.POWER_SAVING -> BeaconIcons.PowerSaving
        ReceiveMode.REALTIME -> BeaconIcons.Realtime
        ReceiveMode.RULES -> BeaconIcons.Tune
    }

@Composable
fun receiveModeColor(mode: ReceiveMode): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    return when (mode) {
        ReceiveMode.POWER_SAVING -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        ReceiveMode.REALTIME -> if (dark) Color(0xFF64B5F6) else Color(0xFF1565C0)
        ReceiveMode.RULES -> if (dark) Color(0xFFFFB74D) else Color(0xFFB45309)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ReceiveModeButton(
    receiving: Boolean,
    mode: ReceiveMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    serverIssue: Boolean = false,
    checkingServer: Boolean = false,
    connected: Boolean = receiving,
) {
    val accent =
        if (connected && !serverIssue && !checkingServer) receiveModeColor(mode)
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f)
    val description =
        if (checkingServer) stringResource(R.string.server_checking)
        else if (serverIssue) stringResource(R.string.server_warning_action)
        else
            stringResource(R.string.settings_reception_page_title) +
                " · " +
                stringResource(receiveModeTitle(mode)) +
                " · " +
                stringResource(
                    if (receiving) R.string.ui_mode_running else R.string.ui_receive_paused_title
                )
    Box(
        Modifier.padding(start = 8.dp)
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.receive_mode_settings_long_press),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize().padding(1.dp)) {
            val width = 1.5.dp.toPx()
            drawCircle(
                color = accent.copy(alpha = .75f),
                radius = size.minDimension / 2 - width / 2,
                style = Stroke(width = width),
            )
        }
        Box(
            Modifier.size(40.dp).background(accent.copy(alpha = .1f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                receiveModeIcon(mode),
                description,
                Modifier.size(26.dp),
                tint = accent,
            )
        }
    }
}

/** Native pull gestures and indicators; the pinned search stays outside this box. */
@Composable
fun BeaconPullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier,
    indicatorPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    if (LocalBeaconUiStyle.current == BeaconUiStyle.MIUIX) {
        top.yukonga.miuix.kmp.basic.PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = modifier,
            contentPadding = indicatorPadding,
            refreshTexts =
                listOf(
                    stringResource(R.string.ui_pull_to_refresh_hint),
                    stringResource(R.string.ui_release_to_refresh_hint),
                    stringResource(R.string.ui_refreshing_hint),
                    stringResource(R.string.ui_refreshed_hint),
                ),
            content = content,
        )
    } else {
        val state = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = modifier,
            state = state,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = state,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(indicatorPadding),
                )
            },
        ) {
            content()
        }
    }
}

/** Small information pills and removable filters share the page's color and radius. */
@Composable
fun BeaconSummaryPill(
    label: String, icon: ImageVector, highlighted: Boolean = false,
    enabled: Boolean = true, onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Surface(
        modifier = if (onClick == null) modifier else modifier
            .heightIn(min = if (compact) InputChipDefaults.Height else BeaconUiMetrics.ControlMinHeight)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        shape = RoundedCornerShape(50),
        color =
            if (highlighted) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = if (compact) 2.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap6, Alignment.CenterHorizontally),
        ) {
            Icon(
                icon,
                null,
                Modifier.size(16.dp),
                tint =
                    if (highlighted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BeaconText(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** KSU-style floating feedback: high contrast, rounded corners and swipe dismissal. */
@Composable
fun BeaconOperationSnackbar(data: SnackbarData) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) data.dismiss()
            true
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {},
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap10),
            ) {
                Icon(
                    BeaconIcons.Info,
                    null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.inversePrimary,
                )
                BeaconText(
                    data.visuals.message,
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
                data.visuals.actionLabel?.let { label ->
                    BeaconTextButton(onClick = data::performAction) {
                        BeaconText(label, color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }
    }
}
