package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Compact two-column actions, with a single column for narrow windows or enlarged fonts. */
@Composable
fun BeaconCompactButtonGrid(labels: List<String>, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: (Int) -> Unit) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 280.dp * scale) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.indices.step(columns).forEach { start ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (index in start until minOf(start + columns, labels.size))
                        BeaconOutlinedButton({ onClick(index) }, Modifier.weight(1f), enabled) { BeaconText(labels[index]) }
                    repeat((start + columns - labels.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
