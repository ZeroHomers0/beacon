package io.github.beacon.app.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R

/** Reports never resize the originating settings card. Repeating an action clears its result first. */
@Composable
fun SettingsOperationFeedback(title: String, result: String, failed: Boolean? = null, visible: Boolean = true) {
    var showing by remember(result) { mutableStateOf(result.isNotBlank()) }
    if (visible && showing) BeaconAlertDialog(
        onDismissRequest = { showing = false },
        title = { BeaconText(title) },
        text = { SelectionContainer { BeaconText(result,
            color = if (failed == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) } },
        confirmButton = { BeaconButton({ showing = false }) { BeaconText(stringResource(R.string.action_close)) } },
    )
}

/** A failed read has one recoverable dialog instead of inserting a banner into the list. */
@Composable
fun SettingsRetryFeedback(title: String, failed: Boolean, onRetry: (() -> Unit)? = null) {
    var showing by remember(failed) { mutableStateOf(failed) }
    if (showing) BeaconAlertDialog(
        onDismissRequest = { showing = false },
        title = { BeaconText(title) },
        text = { SettingsHint(stringResource(R.string.settings_data_failed)) },
        confirmButton = {
            BeaconButton({ showing = false; onRetry?.invoke() }) {
                BeaconText(stringResource(if (onRetry == null) R.string.action_close else R.string.action_retry))
            }
        },
        dismissButton = if (onRetry == null) null else {
            { BeaconCancelButton({ showing = false }) }
        },
    )
}
