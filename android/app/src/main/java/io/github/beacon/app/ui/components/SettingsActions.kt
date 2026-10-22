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
    icon: ImageVector,
    enabled: Boolean = true,
    accessibilityLabel: String = label,
    onClick: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .38f)
    BeaconTextButton(onClick, Modifier.named(accessibilityLabel), enabled = enabled) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap6)) {
            Icon(icon, null, Modifier.size(BeaconUiMetrics.IconInline), tint = tint)
            BeaconText(label, color = tint, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SettingsPrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    BeaconButton(onClick, modifier, enabled) { BeaconText(label) }
}

/** Refresh, copy and overflow share one icon-only, labelled hit target. */
@Composable
fun SettingsToolAction(
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    completed: Boolean = false,
    onClick: () -> Unit,
) {
    BeaconIconButton(onClick = onClick,
        modifier = modifier.sizeIn(minWidth = BeaconUiMetrics.ControlMinHeight, minHeight = BeaconUiMetrics.ControlMinHeight),
        enabled = enabled && !loading) {
        BeaconLoadingIcon(if (completed) BeaconIcons.Check else icon, loading,
            modifier = Modifier.size(BeaconUiMetrics.IconMedium), description = description,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled || loading) 1f else .38f))
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
