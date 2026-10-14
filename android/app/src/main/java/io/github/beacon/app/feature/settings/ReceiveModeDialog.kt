package io.github.beacon.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.ReceiveMode
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
fun ReceiveModeDialog(
    selected: ReceiveMode,
    onSelect: (ReceiveMode) -> Unit,
    onDismiss: () -> Unit,
    connectionStatus: String = "",
    onConnectionSettings: (() -> Unit)? = null,
) {
    BeaconAlertDialog(
        onDismissRequest = onDismiss,
        title = { BeaconText(stringResource(R.string.settings_reception_page_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap)) {
                if (connectionStatus.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BeaconText(connectionStatus, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        onConnectionSettings?.let { action ->
                            BeaconTextButton(onClick = action) {
                                BeaconText(stringResource(R.string.settings_connection_section_title))
                            }
                        }
                    }
                }
                ReceiveMode.entries.forEach { mode ->
                    val available = true
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color =
                            if (mode == selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = available) { onSelect(mode) }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                receiveModeIcon(mode),
                                null,
                                Modifier.size(24.dp),
                                tint =
                                    if (available) receiveModeColor(mode)
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(Modifier.weight(1f)) {
                                BeaconText(
                                    stringResource(receiveModeTitle(mode)),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                BeaconText(
                                    stringResource(
                                        when (mode) {
                                            ReceiveMode.POWER_SAVING ->
                                                R.string.receive_mode_power_description
                                            ReceiveMode.REALTIME ->
                                                R.string.receive_mode_realtime_description
                                            ReceiveMode.RULES ->
                                                R.string.receive_mode_rules_description
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (mode == selected)
                                Icon(BeaconIcons.Check, null, Modifier.size(20.dp))
                        }
                    }
                }
                if (selected == ReceiveMode.REALTIME)
                    BeaconText(
                        stringResource(R.string.receive_mode_pending_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        },
        confirmButton = {
            BeaconButton(onClick = onDismiss) {
                BeaconText(stringResource(R.string.ui_got_it_action))
            }
        },
    )
}
