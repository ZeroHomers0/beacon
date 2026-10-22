@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.beacon.app.ui.icons.BeaconIcons

/** Content-width actions wrap on narrow screens and when accessibility text grows. */
@Composable
fun SettingsActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap4),
        content = content,
    )
}

@Composable
fun SettingsTextAction(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    accessibilityLabel: String = label,
    onClick: () -> Unit,
) {
    val tint = (if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        .copy(alpha = if (enabled) 1f else .38f)
    BeaconTextButton(onClick, Modifier.named(accessibilityLabel), enabled = enabled) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap6)) {
            if (icon != null) Icon(icon, null, Modifier.size(BeaconUiMetrics.IconInline), tint = tint)
            BeaconText(label, color = tint)
        }
    }
}

@Composable
fun SettingsPrimaryButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    BeaconButton(onClick, modifier, enabled) {
        Icon(icon, null, Modifier.size(BeaconUiMetrics.IconInline))
        Spacer(Modifier.width(BeaconUiMetrics.Gap6))
        BeaconText(label)
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
