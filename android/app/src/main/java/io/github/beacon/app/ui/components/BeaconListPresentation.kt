@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.beacon.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
        ReceiveMode.RULES -> BeaconIcons.ReceiveRules
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
    visible: Boolean = true,
) {
    // One shared gate: the system switch, the animator scale and this composition all agree now.
    val animationsEnabled = rememberSystemAnimationsEnabled()
    val modeColor = receiveModeColor(mode)
    val accent by animateColorAsState(
        if (serverIssue) MaterialTheme.colorScheme.error
        else if (connected && !checkingServer) modeColor
        else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(BeaconMotion.StandardMs), label = "receive-mode-color",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .90f else 1f, tween(BeaconMotion.QuickMs), label = "receive-mode-press")
    var resumed by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    val animateStatus = resumed && visible && animationsEnabled
    val breathing = animateStatus && receiving && connected && !serverIssue && !checkingServer
    val badgeAlpha = remember { Animatable(1f) }
    LaunchedEffect(breathing) {
        badgeAlpha.snapTo(1f)
        if (breathing) {
            while (true) {
                kotlinx.coroutines.delay(BeaconMotion.BreathPeriodMs.toLong())
                badgeAlpha.animateTo(.55f, tween(BeaconMotion.BreathFadeMs))
                badgeAlpha.animateTo(1f, tween(BeaconMotion.BreathFadeMs))
            }
        }
    }
    val angle = if (checkingServer && animateStatus) {
        val motion = rememberInfiniteTransition(label = "receive-mode-check")
        motion.animateFloat(
            0f, 360f, infiniteRepeatable(tween(BeaconMotion.PulseMs, easing = LinearEasing)),
            label = "receive-mode-check-angle",
        )
    } else {
        rememberUpdatedState(0f)
    }
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
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(bounded = false, radius = 24.dp),
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.receive_mode_settings_long_press),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(42.dp).background(
                accent.copy(alpha = .14f).compositeOver(MaterialTheme.colorScheme.surface),
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                mode,
                transitionSpec = {
                    (fadeIn(tween(240)) + scaleIn(tween(280), initialScale = .7f)) togetherWith
                        (fadeOut(tween(140)) + scaleOut(tween(180), targetScale = 1.2f))
                },
                label = "receive-mode-icon",
            ) { displayed ->
                Icon(receiveModeIcon(displayed), description, Modifier.size(26.dp), tint = accent)
            }
        }
        if (checkingServer) {
            Canvas(Modifier.matchParentSize().padding(1.dp)) {
                drawArc(
                    modeColor, angle.value - 90f, 110f, false,
                    style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        } else {
            val badgeColor = if (serverIssue) MaterialTheme.colorScheme.error
                else if (receiving && connected) io.github.beacon.app.ui.theme.beaconSuccessColor()
                else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                Modifier.align(Alignment.BottomEnd).padding(2.dp).size(14.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp)
                    .graphicsLayer { alpha = if (breathing) badgeAlpha.value else 1f }
                    .background(badgeColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (serverIssue || !receiving || !connected) Canvas(Modifier.size(6.dp)) {
                    val stroke = 1.4.dp.toPx()
                    if (serverIssue) {
                        drawLine(Color.White, androidx.compose.ui.geometry.Offset(center.x, 0f),
                            androidx.compose.ui.geometry.Offset(center.x, size.height * .55f), stroke, StrokeCap.Round)
                        drawCircle(Color.White, stroke / 2, androidx.compose.ui.geometry.Offset(center.x, size.height))
                    } else drawLine(Color.White, androidx.compose.ui.geometry.Offset(0f, center.y),
                        androidx.compose.ui.geometry.Offset(size.width, center.y), stroke, StrokeCap.Round)
                }
            }
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

/**
 * A count that lives next to the search field instead of on its own row: one glyph plus the compact
 * number, widening only when the number outgrows the slot. Screen readers hear the full value, and
 * the highlighted state reuses the pill language of the filters and the saved-search chips.
 */
@Composable
fun BeaconCountPill(
    count: Long,
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tint =
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier
            .defaultMinSize(minWidth = BeaconUiMetrics.CountChipMinWidth)
            .heightIn(min = BeaconUiMetrics.CountChipHeight)
            .animateContentSize(MotionResize)
            .clip(BeaconUiMetrics.PillShape)
            .then(
                if (selected)
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        shape = BeaconUiMetrics.PillShape,
                    )
                else Modifier
            )
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .clickable(
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = description,
                onClick = onClick,
            ),
        shape = BeaconUiMetrics.PillShape,
        color =
            if (selected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    .compositeOver(MaterialTheme.colorScheme.surface)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = BeaconUiMetrics.CountChipPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap6, Alignment.CenterHorizontally),
        ) {
            Icon(icon, null, Modifier.size(BeaconUiMetrics.IconInline), tint = tint)
            BeaconText(
                formatCompactCount(count),
                style =
                    MaterialTheme.typography.labelLarge.copy(
                        fontFeatureSettings = "tnum",
                    ),
                color =
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
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
