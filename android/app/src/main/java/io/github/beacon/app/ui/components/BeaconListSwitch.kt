package io.github.beacon.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** A shared capsule with a spring-driven ink slider; retains the former compact pill height. */
@Composable
fun BeaconListSwitch(
    labels: List<String>,
    icons: List<ImageVector>,
    selectedIndex: Int = -1,
    enabled: Boolean = true,
    selectableIndices: Set<Int> = labels.indices.toSet(),
    onSelected: ((Int) -> Unit)? = null,
) {
    if (labels.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelMedium.copy(textAlign = TextAlign.Center)
    val labelHeight = with(androidx.compose.ui.platform.LocalDensity.current) { labelStyle.fontSize.toDp() * 1.4f }
    val height = maxOf(if (onSelected != null) androidx.compose.material3.InputChipDefaults.Height else 0.dp,
        maxOf(labelHeight, BeaconUiMetrics.IconSmall) + BeaconUiMetrics.Gap2 * 2)
    val dark = scheme.background.luminance() < .5f
    val position by animateFloatAsState(selectedIndex.coerceAtLeast(0).toFloat(),
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "list-switch-position")
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(scheme.surfaceContainerHigh)) {
        if (selectedIndex in labels.indices) Canvas(Modifier.matchParentSize()) {
            val inset = BeaconUiMetrics.SummarySwitchInset.toPx()
            val cell = (size.width - inset * 2) / labels.size
            val x = if (rtl) labels.lastIndex - position else position
            val height = (size.height - inset * 2).coerceAtLeast(0f)
            drawRoundRect(scheme.onSurface.copy(alpha = if (dark) .13f else .075f),
                topLeft = Offset(inset + cell * x, inset), size = Size(cell, height),
                cornerRadius = CornerRadius(height / 2))
        }
        Row(Modifier.fillMaxSize()
            .then(if (onSelected != null) Modifier.selectableGroup() else Modifier)) {
            labels.forEachIndexed { index, label ->
                val active = index == selectedIndex
                val interactive = onSelected != null && index in selectableIndices
                val foreground = scheme.onSurface.copy(alpha = when {
                    onSelected == null || active -> 1f
                    else -> .5f
                })
                Row(Modifier.weight(1f).fillMaxHeight()
                    .heightIn(min = if (onSelected != null) androidx.compose.material3.InputChipDefaults.Height else 0.dp)
                    .then(if (interactive) Modifier.selectable(selected = active, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, enabled = enabled,
                        role = Role.Tab, onClick = { onSelected?.invoke(index) }) else Modifier)
                    .padding(horizontal = BeaconUiMetrics.Gap12, vertical = BeaconUiMetrics.Gap2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap6, Alignment.CenterHorizontally)) {
                    androidx.compose.material3.Icon(icons[index], null, Modifier.size(BeaconUiMetrics.IconSmall), tint = foreground)
                    BeaconFittedText(label, Modifier.weight(1f), color = foreground,
                        style = labelStyle)
                }
            }
        }
    }
}

/** Animate the existing result layer, keeping its list state and side effects mounted once. */
@Composable
fun Modifier.beaconFilterChange(filter: Int): Modifier {
    val alpha = remember { Animatable(1f) }
    var previous by remember { mutableIntStateOf(filter) }
    LaunchedEffect(filter) {
        if (previous != filter) {
            previous = filter
            alpha.snapTo(.94f)
            alpha.animateTo(1f, tween(BeaconMotion.QuickMs))
        }
    }
    return graphicsLayer { this.alpha = alpha.value }
}
