package io.github.beacon.app.feature.settings

import io.github.beacon.app.ui.components.BeaconUiMetrics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.ReceiveMode
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

/** All modes remain visible; selecting another mode retains the draft-discard safeguard. */
@Composable
internal fun ReceiveModeOptions(
    selected: ReceiveMode,
    enabled: Boolean,
    onSelect: (ReceiveMode) -> Unit,
    grouped: Boolean = true,
) {
    val choices: @Composable () -> Unit = {
        Column(Modifier.selectableGroup()) {
            listOf(ReceiveMode.REALTIME, ReceiveMode.POWER_SAVING, ReceiveMode.RULES)
                .forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().alpha(if (enabled) 1f else .38f)
                            .selectable(selected = mode == selected, enabled = enabled,
                                role = Role.RadioButton, onClick = { onSelect(mode) })
                            .padding(BeaconUiMetrics.CardInset),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap10),
                    ) {
                        Icon(receiveModeIcon(mode), null, Modifier.size(20.dp),
                            tint = receiveModeColor(mode))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap4)) {
                            BeaconFittedText(stringResource(receiveModeTitle(mode)), Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.titleSmall)
                            BeaconFittedText(stringResource(when (mode) {
                                ReceiveMode.REALTIME -> R.string.receive_mode_realtime_description
                                ReceiveMode.POWER_SAVING -> R.string.receive_mode_power_description
                                ReceiveMode.RULES -> R.string.receive_mode_rules_description
                            }), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.size(20.dp)) {
                            if (mode == selected) Icon(BeaconIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
        }
    }
    if (grouped) SettingsGroup(stringResource(R.string.settings_receive_mode_section)) { choices() }
    else choices()
}

@Composable
fun ReceiveModeDialog(selected: ReceiveMode, onSelect: (ReceiveMode) -> Unit, onDismiss: () -> Unit) {
    BeaconAlertDialog(onDismissRequest = onDismiss,
        title = { BeaconText(stringResource(R.string.ui_v2_receive_mode_dialog_title)) },
        text = { ReceiveModeOptions(selected, true, onSelect, grouped = false) },
        confirmButton = { BeaconButton(onDismiss) { BeaconText(stringResource(R.string.action_close)) } })
}
