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
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
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
    val context = androidx.compose.ui.platform.LocalContext.current
    var animationsEnabled by remember { mutableStateOf(android.animation.ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animationsEnabled = android.animation.ValueAnimator.areAnimatorsEnabled()
            }
        }
        context.contentResolver.registerContentObserver(
            android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val modeColor = receiveModeColor(mode)
    val accent by animateColorAsState(
        if (serverIssue) MaterialTheme.colorScheme.error
        else if (connected && !checkingServer) modeColor
        else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(200), label = "receive-mode-color",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .90f else 1f, tween(180), label = "receive-mode-press")
    var resumed by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumed = true
        animationsEnabled = android.animation.ValueAnimator.areAnimatorsEnabled()
        onPauseOrDispose { resumed = false }
    }
    val animateStatus = resumed && visible && animationsEnabled
    val breathing = animateStatus && receiving && connected && !serverIssue && !checkingServer
    val badgeAlpha = remember { Animatable(1f) }
    LaunchedEffect(breathing) {
        badgeAlpha.snapTo(1f)
        if (breathing) {
            while (true) {
                kotlinx.coroutines.delay(1600)
                badgeAlpha.animateTo(.55f, tween(700))
                badgeAlpha.animateTo(1f, tween(700))
            }
        }
    }
    val angle = if (checkingServer && animateStatus) {
        val motion = rememberInfiniteTransition(label = "receive-mode-check")
        motion.animateFloat(
            0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)),
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
