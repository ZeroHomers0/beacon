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
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.saveable.rememberSaveable
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.NumberPickerDefaults
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
    Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap10)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap10)) {
            BeaconTimePreference(start, onStartChange, startLabel, Modifier.weight(1f), enabled)
            BeaconTimePreference(end, onEndChange, endLabel, Modifier.weight(1f), enabled)
        }
        val startMinute = timeMinute(start)
        val endMinute = timeMinute(end)
        BeaconFittedText(
            if (startMinute == null || endMinute == null || startMinute == endMinute)
                stringResource(R.string.quiet_invalid)
            else stringResource(if (startMinute > endMinute) R.string.time_range_overnight
                else R.string.time_range_same_day, end),
            Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            verticalArrangement =
                Arrangement.spacedBy(BeaconUiMetrics.Gap6, androidx.compose.ui.Alignment.CenterVertically),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            BeaconText(
                label,
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            BeaconText(
                value,
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
    if (open) {
        val minute = timeMinute(value) ?: 0
        if (miuix()) {
            var hour by rememberSaveable { mutableIntStateOf(minute / 60) }
            var selectedMinute by rememberSaveable { mutableIntStateOf(minute % 60) }
            val hourLabel = stringResource(R.string.time_picker_hour)
            val minuteLabel = stringResource(R.string.time_picker_minute)
            val colors = NumberPickerDefaults.colors(
                selectedTextColor = MaterialTheme.colorScheme.primary,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BeaconAlertDialog(
                onDismissRequest = { open = false },
                title = { BeaconText(label) },
                text = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Surface(
                            Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {}
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8),
                        ) {
                            NumberPicker(
                                value = hour,
                                onValueChange = { hour = it },
                                range = 0..23,
                                label = { it.toString().padStart(2, '0') },
                                wrapAround = true,
                                visibleItemCount = 3,
                                colors = colors,
                                itemHeight = 48.dp,
                                textStyle = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.weight(1f).semantics { contentDescription = hourLabel },
                            )
                            BeaconText(":", style = MaterialTheme.typography.headlineMedium)
                            NumberPicker(
                                value = selectedMinute,
                                onValueChange = { selectedMinute = it },
                                range = 0..59,
                                label = { it.toString().padStart(2, '0') },
                                wrapAround = true,
                                visibleItemCount = 3,
                                colors = colors,
                                itemHeight = 48.dp,
                                textStyle = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.weight(1f).semantics { contentDescription = minuteLabel },
                            )
                        }
                    }
                },
                confirmButton = {
                    BeaconButton(onClick = {
                        onChange(String.format(Locale.ROOT, "%02d:%02d", hour, selectedMinute))
                        open = false
                    }) { BeaconText(stringResource(R.string.settings_confirm)) }
                },
                dismissButton = { BeaconCancelButton(onClick = { open = false }) },
            )
        } else {
            val picker = rememberTimePickerState(minute / 60, minute % 60, true)
            BeaconAlertDialog(
                onDismissRequest = { open = false },
                title = { BeaconText(label) },
                text = {
                    Column(
                        Modifier.fillMaxWidth(),
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
                        BeaconText(stringResource(R.string.settings_confirm))
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

@Composable
fun BeaconTimeSummary(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = BeaconUiMetrics.FieldMinHeight)
        .padding(vertical = BeaconUiMetrics.FieldVerticalInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap2)) {
            BeaconFittedText(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
            BeaconFittedText(value, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Icon(io.github.beacon.app.ui.icons.BeaconIcons.Arrow, null, Modifier.size(18.dp))
    }
}
