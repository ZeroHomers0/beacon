@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import io.github.beacon.app.ui.icons.BeaconIcons

/** Full-width single action, equal-width peers; large labels stack without changing hierarchy. */
@Composable
fun SettingsActions(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalActionFillWidth provides true) {
        Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
            if (measurables.isEmpty()) {
                layout(constraints.maxWidth, 0) {}
            } else {
                val gap = BeaconUiMetrics.Gap8.roundToPx()
                val count = measurables.size
                val available = (constraints.maxWidth - gap * (count - 1)).coerceAtLeast(0)
                val cellWidth = available / count
                val horizontal = measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= cellWidth }
                val widths = List(count) { index ->
                    if (horizontal) cellWidth + if (index < available % count) 1 else 0
                    else constraints.maxWidth
                }
                val height = measurables.mapIndexed { index, item -> item.maxIntrinsicHeight(widths[index]) }.max()
                val placeables = measurables.mapIndexed { index, item ->
                    item.measure(Constraints.fixed(widths[index], height))
                }
                val totalHeight = if (horizontal) height else height * count + gap * (count - 1)
                layout(constraints.maxWidth, constraints.constrainHeight(totalHeight)) {
                    var offset = 0
                    placeables.forEach {
                        it.placeRelative(if (horizontal) offset else 0, if (horizontal) 0 else offset)
                        offset += (if (horizontal) it.width else it.height) + gap
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTextAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    accessibilityLabel: String = label,
    onClick: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .38f)
    BeaconTextButton(onClick, Modifier.named(accessibilityLabel), enabled = enabled) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap6)) {
            Icon(icon, null, Modifier.size(BeaconUiMetrics.IconInline), tint = tint)
            BeaconText(label, color = tint, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SettingsPrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    BeaconButton(onClick, modifier.fillMaxWidth(), enabled) { BeaconText(label) }
}

/** Refresh, copy and overflow share one icon-only, labelled hit target. */
@Composable
fun SettingsToolAction(
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    completed: Boolean = false,
    onClick: () -> Unit,
) {
    BeaconIconButton(onClick = onClick,
        modifier = modifier.sizeIn(minWidth = BeaconUiMetrics.ControlMinHeight, minHeight = BeaconUiMetrics.ControlMinHeight)
            .semantics { if (loading) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
        enabled = enabled && !loading) {
        BeaconLoadingIcon(if (completed) BeaconIcons.Check else icon, loading,
            modifier = Modifier.size(BeaconUiMetrics.IconMedium), description = description,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled || loading) 1f else .38f))
    }
}

/** An action in a list has no navigation chevron. Destructive actions remain explicit. */
@Composable
fun SettingsCommandRow(
    title: String,
    subtitle: String = "",
    icon: ImageVector = BeaconIcons.Settings,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    SettingsRow(title, subtitle, icon,
        if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        onClick, enabled = enabled, command = true, danger = danger)
}
