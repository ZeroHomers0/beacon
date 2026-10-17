package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Prefer two actions per row whenever their actual button contents fit. */
@Composable
fun BeaconCompactButtonGrid(labels: List<String>, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: (Int) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
        labels.indices.step(2).forEach { first ->
            if (first + 1 < labels.size) {
                BeaconActionPair(
                    { BeaconOutlinedButton({ onClick(first) }, enabled = enabled) { BeaconText(labels[first]) } },
                    { BeaconOutlinedButton({ onClick(first + 1) }, enabled = enabled) { BeaconText(labels[first + 1]) } },
                )
            } else {
                BeaconOutlinedButton({ onClick(first) }, Modifier.fillMaxWidth(), enabled) { BeaconText(labels[first]) }
            }
        }
    }
}
