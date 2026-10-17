package io.github.beacon.app.feature.messages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.constrainHeight
import io.github.beacon.app.domain.policy.metadataLabelWidths
import io.github.beacon.app.ui.components.BeaconUiMetrics

@Composable
internal fun MessageMetadataRow(hasAction: Boolean, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { children, constraints ->
        val gap = BeaconUiMetrics.Gap6.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val action = if (hasAction) children.lastOrNull()?.measure(loose) else null
        val labels = if (hasAction) children.dropLast(1) else children
        val gaps = (children.size - 1).coerceAtLeast(0) * gap
        val widths = metadataLabelWidths(
            labels.map { it.maxIntrinsicWidth(constraints.maxHeight) },
            constraints.maxWidth - (action?.width ?: 0) - gaps,
        )
        val measured = labels.mapIndexed { index, child ->
            child.measure(loose.copy(maxWidth = widths[index]))
        }
        val height = constraints.constrainHeight(
            maxOf(measured.maxOfOrNull { it.height } ?: 0, action?.height ?: 0)
        )
        layout(constraints.maxWidth, height) {
            var position = 0
            measured.forEach { label ->
                label.placeRelative(position, (height - label.height) / 2)
                position += label.width + gap
            }
            action?.placeRelative(constraints.maxWidth - action.width, (height - action.height) / 2)
        }
    }
}
