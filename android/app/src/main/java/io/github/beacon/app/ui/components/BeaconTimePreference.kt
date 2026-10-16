@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import java.util.Locale

@Composable
fun BeaconTimeRangePreference(
    start: String,
    end: String,
    startLabel: String,
    endLabel: String,
    enabled: Boolean = true,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BeaconTimePreference(start, onStartChange, startLabel, Modifier.weight(1f), enabled)
            BeaconTimePreference(end, onEndChange, endLabel, Modifier.weight(1f), enabled)
        }
        val startMinute = timeMinute(start)
        val endMinute = timeMinute(end)
        if (startMinute != null && endMinute != null && startMinute != endMinute) {
            SettingsHint(
                stringResource(
                    if (startMinute > endMinute) R.string.time_range_overnight
                    else R.string.time_range_same_day,
                    end,
                )
            )
        }
    }
}

@Composable
fun BeaconTimePreference(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Surface(
        onClick = { open = true },
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(14.dp).heightIn(min = 56.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BeaconText(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BeaconText(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
    if (open) {
        val minute = timeMinute(value) ?: 0
        if (miuix()) {
            val context = LocalContext.current
            DisposableEffect(context, minute) {
                val dialog =
                    android.app.TimePickerDialog(
                        context,
                        { _, hour, selectedMinute ->
                            onChange(String.format(Locale.ROOT, "%02d:%02d", hour, selectedMinute))
                            open = false
                        },
                        minute / 60,
                        minute % 60,
                        true,
                    )
                dialog.setTitle(label)
                dialog.setOnDismissListener { open = false }
                dialog.show()
                onDispose { dialog.dismiss() }
            }
        } else {
            val picker = rememberTimePickerState(minute / 60, minute % 60, true)
            BeaconAlertDialog(
                onDismissRequest = { open = false },
                title = { BeaconText(label) },
                text = {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        TimePicker(state = picker)
                    }
                },
                confirmButton = {
                    BeaconButton(
                        onClick = {
                            onChange(
                                String.format(Locale.ROOT, "%02d:%02d", picker.hour, picker.minute)
                            )
                            open = false
                        }
                    ) {
                        BeaconText(stringResource(R.string.action_save))
                    }
                },
                dismissButton = {
                    BeaconCancelButton(onClick = { open = false })
                },
            )
        }
    }
}

private fun timeMinute(value: String): Int? {
    val parts = value.split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}
