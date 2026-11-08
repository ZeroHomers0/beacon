package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Read-only counts must not look like selectable tabs or filters. */
@Composable
internal fun BeaconListSummary(total: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = BeaconUiMetrics.Gap4),
        horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
        BeaconText(listOfNotNull(total, detail?.takeIf { it.isNotBlank() }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = BeaconListText.MetadataSize, lineHeight = BeaconListText.MetadataLineHeight),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
