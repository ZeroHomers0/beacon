package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
) {
    BeaconAlertDialog(
        onDismissRequest = onDismiss,
        title = { BeaconText(stringResource(R.string.ui_v2_receive_mode_dialog_title)) },
        text = {
            Column(
                Modifier.selectableGroup()
                    .then(
                        if (miuix()) Modifier else Modifier.verticalScroll(rememberScrollState())
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(ReceiveMode.REALTIME, ReceiveMode.POWER_SAVING, ReceiveMode.RULES).forEach {
                    mode ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color =
                            if (mode == selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = BeaconUiMetrics.ControlMinHeight)
                                .selectable(
                                    selected = mode == selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(mode) },
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                receiveModeIcon(mode),
                                null,
                                Modifier.size(20.dp),
                                tint = receiveModeColor(mode),
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
