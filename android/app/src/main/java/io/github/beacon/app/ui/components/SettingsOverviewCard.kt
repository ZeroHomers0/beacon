package io.github.beacon.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.beaconSuccessColor

/** Two real text lines and an overlaid status mark: data changes never insert layout slots. */
@Composable
fun SettingsOverviewCard(
    title: String,
    status: String,
    subtitle: String,
    healthy: Boolean,
    error: Boolean = false,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tone = when { healthy -> beaconSuccessColor(); error -> scheme.error; else -> scheme.onSurfaceVariant }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SettingsLabel(title, Modifier.weight(1f))
            action?.invoke()
        }
        BeaconCard(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = tone.copy(alpha = if (healthy) .16f else .06f).compositeOver(scheme.surfaceContainerLow))) {
            Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)) {
                // A match-parent overlay never contributes to the measured height.
                Box(Modifier.matchParentSize()) {
                    if (healthy) Box(
                        Modifier.align(Alignment.BottomEnd).offset(x = 16.dp, y = 20.dp)
                            .size(88.dp).border(8.dp, tone.copy(alpha = .85f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.Canvas(Modifier.size(60.dp)) {
                            val stroke = 8.dp.toPx()
                            val color = tone.copy(alpha = .85f)
                            drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .18f, size.height * .52f),
                                androidx.compose.ui.geometry.Offset(size.width * .42f, size.height * .75f), stroke,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .42f, size.height * .75f),
                                androidx.compose.ui.geometry.Offset(size.width * .84f, size.height * .28f), stroke,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(start = BeaconUiMetrics.CardInset,
                    top = BeaconUiMetrics.CardInset, end = 80.dp, bottom = BeaconUiMetrics.CardInset),
                    verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap4)) {
                    BeaconFittedText(status, Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    BeaconFittedText(subtitle, Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        inspectable = true, inspectTitle = title)
                }
            }
        }
        BeaconCard(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(BeaconUiMetrics.CardInset),
                verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap), content = content)
        }
    }
}

/** Every fact uses a single-line title and a single-line value, including unknown data. */
@Composable
fun SettingsOverviewField(title: String, value: String, error: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap2)) {
        BeaconFittedText(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BeaconFittedText(value.lineSequence().filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { BeaconUnknownValue }, Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            inspectable = true, inspectTitle = title, fullText = value)
    }
}
