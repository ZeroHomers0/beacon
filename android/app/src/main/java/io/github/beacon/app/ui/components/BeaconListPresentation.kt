@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.beacon.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
) {
    val accent = if (serverIssue) MaterialTheme.colorScheme.error else receiveModeColor(mode)
    val description =
        if (serverIssue) stringResource(R.string.server_warning_action)
        else if (checkingServer) stringResource(R.string.server_checking)
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
        if (receiving && !serverIssue && !checkingServer) {
            val transition = rememberInfiniteTransition(label = "Reception running")
            val rotation by
                transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec =
                        infiniteRepeatable(tween(12000, easing = LinearEasing), RepeatMode.Restart),
                    label = "Dashed ring rotation",
                )
            Canvas(
                Modifier.matchParentSize().padding(1.dp).graphicsLayer { rotationZ = rotation }
            ) {
                val width = 1.5.dp.toPx()
                drawCircle(
                    color = accent.copy(alpha = .75f),
                    radius = size.minDimension / 2 - width / 2,
                    style =
                        Stroke(
                            width = width,
                            pathEffect =
                                PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                        ),
                )
            }
        }
        Box(
            Modifier.size(40.dp).background(accent.copy(alpha = .1f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (serverIssue) BeaconIcons.Warning
                else if (checkingServer) BeaconIcons.Sync else receiveModeIcon(mode),
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
fun BeaconSummaryPill(label: String, icon: ImageVector, highlighted: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(50),
        color =
            if (highlighted) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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

/** A themed floating result, rather than Material's inverse-color snackbar inside MIUIX. */
@Composable
fun BeaconOperationSnackbar(data: SnackbarData) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                BeaconIcons.Info,
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            BeaconText(
                data.visuals.message,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            data.visuals.actionLabel?.let { label ->
                BeaconTextButton(onClick = data::performAction) {
                    BeaconText(label, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
