package io.github.beacon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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
    val iconSize = 32.dp
    val iconGap = 12.dp
    Column(
        Modifier.fillMaxWidth()
            .heightIn(min = BeaconUiMetrics.FieldMinHeight)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(iconGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(iconSize)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(20.dp), tint = tint)
            }
            BeaconText(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
        }
        if (details.isNotBlank()) {
            Box(Modifier.padding(start = iconSize + iconGap)) { SettingsHint(details) }
        }
    }
}

/** Results remain complete, including multiline diagnostic and backup reports. */
@Composable
fun OperationResultPanel(title: String, result: String) {
    SettingsStatusSummary(title, result)
}
