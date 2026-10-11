package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.CleanupPolicy
import io.github.beacon.app.ui.components.*

@Composable
fun CustomCleanupDialog(
    policy: CleanupPolicy,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (CleanupPolicy) -> Unit,
) {
    var days by remember(policy) { mutableStateOf(policy.days.toString()) }
    var limit by remember(policy) { mutableStateOf(policy.maxMessages.toString()) }
    // Fields are pre-filled from the saved policy, so a range error can be attributed to the
    // field that caused it as soon as the user edits it; only the empty case waits for a submit.
    var attempted by remember { mutableStateOf(false) }
    val daysValue = days.toIntOrNull()
    val limitValue = limit.toIntOrNull()
    val daysInvalid = (attempted || days.isNotEmpty()) && daysValue !in DAYS_RANGE
    val limitInvalid = (attempted || limit.isNotEmpty()) && limitValue !in LIMIT_RANGE
    BeaconAlertDialog(
        onDismissRequest = onDismiss,
        title = { BeaconText(stringResource(R.string.storage_custom)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BeaconTextField(
                    days,
                    { days = it.take(5) },
                    Modifier.fillMaxWidth(),
                    labelText = stringResource(R.string.storage_custom_days),
                    label = { BeaconText(stringResource(R.string.storage_custom_days)) },
                    singleLine = true,
                    isError = daysInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                BeaconTextField(
                    limit,
                    { limit = it.take(7) },
                    Modifier.fillMaxWidth(),
                    labelText = stringResource(R.string.storage_max_messages),
                    label = { BeaconText(stringResource(R.string.storage_max_messages)) },
                    singleLine = true,
                    isError = limitInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (daysInvalid || limitInvalid)
                    BeaconText(
                        stringResource(R.string.storage_custom_invalid),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    )
            }
        },
        confirmButton = {
            BeaconButton(
                enabled = !busy,
                onClick = {
                    attempted = true
                    val value = runCatching {
                        policy
                            .copy(
                                days = requireNotNull(daysValue),
                                maxMessages = requireNotNull(limitValue),
                            )
                            .also { it.validate() }
                    }
                        .getOrNull()
                    if (value != null) onSave(value)
                },
            ) {
                BeaconText(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            BeaconOutlinedButton(enabled = !busy, onClick = onDismiss) {
                BeaconText(stringResource(R.string.action_cancel))
            }
        },
    )
}

private val DAYS_RANGE = 0..3650
private val LIMIT_RANGE = 1..100000
