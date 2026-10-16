package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.policy.DisplayTime

@Composable
internal fun MessageCardFooter(
    message: IncomingMessage,
    busy: Boolean,
    selectionMode: Boolean,
    onFilterApplication: (() -> Unit)?,
    onFilterGroup: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BeaconText(
                if (message.createdAtMs <= 0) stringResource(R.string.ui_unknown_label) else DisplayTime.clock(message.createdAtMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (message.reminderPending) BeaconText(
                stringResource(R.string.msg_reminder_pending_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        FooterTags(Modifier.weight(1f)) {
            if (message.applicationId.isNotBlank()) MessageFilterTag(
                label = message.applicationName.ifBlank { stringResource(R.string.message_application_short, message.applicationId.take(8)) },
                compact = true,
                enabled = !busy && !selectionMode && onFilterApplication != null,
                onClick = { onFilterApplication?.invoke() },
            )
            if (message.group.isNotBlank()) MessageFilterTag(
                label = message.group,
                compact = true,
                enabled = !busy && !selectionMode && onFilterGroup != null,
                onClick = { onFilterGroup?.invoke() },
            )
        }
    }
}

/** Keep short labels at their natural width and give the remaining space to the longer label. */
@Composable
private fun FooterTags(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { children, constraints ->
        val gap = if (children.size > 1) 4.dp.roundToPx() else 0
        val available = (constraints.maxWidth - gap).coerceAtLeast(0)
        val widths = children.map { it.maxIntrinsicWidth(constraints.maxHeight) }.toMutableList()
        if (widths.size == 1) widths[0] = widths[0].coerceAtMost(available)
        else if (widths.size == 2 && widths.sum() > available) {
            val half = available / 2
            when {
                widths[0] <= half -> widths[1] = available - widths[0]
                widths[1] <= half -> widths[0] = available - widths[1]
                else -> { widths[0] = half; widths[1] = available - half }
            }
        }
        val placeables = children.mapIndexed { index, child ->
            child.measure(constraints.copy(minWidth = 0, maxWidth = widths[index], minHeight = 0))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            var x = constraints.maxWidth - placeables.sumOf { it.width } - gap
            placeables.forEach { child ->
                child.placeRelative(x, (height - child.height) / 2)
                x += child.width + gap
            }
        }
    }
}
