package io.github.beacon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.ui.icons.BeaconIcons

/** A status is separate from editable fields; every detail remains visible. */
@Composable
fun SettingsStatusSummary(
    title: String,
    details: String,
    icon: ImageVector = BeaconIcons.Diagnostics,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    loading: Boolean = false,
    interactive: Boolean = true,
    singleLineDetails: Boolean = false,
    selectableDetails: Boolean = false,
    caption: String? = null,
) {
    val iconSize = BeaconUiMetrics.StatusIconSize
    val displayedTint by androidx.compose.animation.animateColorAsState(
        tint,
        animationSpec = androidx.compose.animation.core.tween(BeaconMotion.StandardMs),
        label = "connection-status-color",
    )
    var showingDetails by remember { mutableStateOf(false) }
    var settledTitle by remember { mutableStateOf(title) }
    var settledDetails by remember { mutableStateOf(details) }
    androidx.compose.runtime.LaunchedEffect(title, details, loading) {
        if (!loading) {
            settledTitle = title
            settledDetails = details
        }
    }
    val displayedTitle = if (loading) settledTitle else title
    val displayedDetails = if (loading) settledDetails else details
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (interactive)
                    Modifier.clickable(
                        enabled = displayedDetails.isNotBlank(),
                        role = androidx.compose.ui.semantics.Role.Button,
                    ) {
                        showingDetails = true
                    }
                else Modifier
            )
            .padding(vertical = 4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = iconSize),
            horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(iconSize)
                    .clip(RoundedCornerShape(14.dp))
                    .background(displayedTint.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                BeaconLoadingIcon(icon, loading, Modifier.size(26.dp), tint = displayedTint)
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap2),
            ) {
                androidx.compose.animation.Crossfade(
                    targetState = displayedTitle,
                    modifier = Modifier.fillMaxWidth(),
                    animationSpec = androidx.compose.animation.core.tween(BeaconMotion.QuickMs),
                    label = "connection-status-title",
                ) { label ->
                    BeaconText(
                        label,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    )
                }
                if (displayedDetails.isNotBlank()) {
                    val content: @Composable () -> Unit = {
                        BeaconText(
                            displayedDetails,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (singleLineDetails) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (selectableDetails)
                        androidx.compose.foundation.text.selection.SelectionContainer { content() }
                    else content()
                }
                if (!caption.isNullOrBlank()) BeaconText(caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (interactive && displayedDetails.isNotBlank())
                Icon(
                    BeaconIcons.Arrow,
                    stringResource(R.string.settings_view_details),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
    if (interactive && showingDetails)
        BeaconAlertDialog(
            onDismissRequest = { showingDetails = false },
            title = { BeaconText(displayedTitle) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        BeaconText(displayedDetails)
                    }
                }
            },
            confirmButton = {
                BeaconButton(onClick = { showingDetails = false }) {
                    BeaconText(stringResource(R.string.action_close))
                }
            },
        )
}

/** Results remain complete, including multiline diagnostic and backup reports. */
@Composable
fun OperationResultPanel(title: String, result: String, failed: Boolean? = null) {
    if (failed == false && result.length <= 100 && !result.contains('\n')) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                BeaconIcons.Check,
                null,
                Modifier.size(20.dp),
                tint = io.github.beacon.app.ui.theme.beaconSuccessColor(),
            )
            BeaconText(result, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    SettingsStatusSummary(
        title,
        result,
        icon =
            when (failed) {
                true -> BeaconIcons.Warning
                false -> BeaconIcons.Check
                null -> BeaconIcons.Info
            },
        tint =
            when (failed) {
                true -> MaterialTheme.colorScheme.error
                false -> io.github.beacon.app.ui.theme.beaconSuccessColor()
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}
/** Compact metadata wraps naturally instead of reserving empty detail lines. */
@Composable
fun SettingsStatusDetail(title: String, details: String, error: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = BeaconUiMetrics.Gap4),
        verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap2),
    ) {
        BeaconText(title, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BeaconText(details, style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}
