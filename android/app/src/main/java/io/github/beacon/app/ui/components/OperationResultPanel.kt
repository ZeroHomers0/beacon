package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.beacon.app.ui.icons.BeaconIcons

/** A status is separate from editable fields; every detail remains visible. */
@Composable
fun SettingsStatusSummary(
    title: String,
    details: String,
    icon: ImageVector = BeaconIcons.Diagnostics,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BeaconText(title, style = MaterialTheme.typography.bodyLarge)
                if (details.isNotBlank()) SettingsHint(details)
            }
        }
    }
}

/** Results remain complete, including multiline diagnostic and backup reports. */
@Composable
fun OperationResultPanel(title: String, result: String) {
    SettingsStatusSummary(title, result)
}
