package io.github.beacon.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R

/** Confirm writes only after the saved values or successful callback arrive. */
@Composable
fun BeaconSaveFeedback(
    changed: Boolean = false,
    saving: Boolean = false,
    failed: Boolean = false,
    saved: Boolean = false,
) {
    val label = when {
        saving -> R.string.settings_saving
        failed -> R.string.settings_save_failed
        changed -> R.string.settings_unsaved_changes
        saved -> R.string.settings_saved
        else -> return
    }
    BeaconText(
        stringResource(label),
        style = MaterialTheme.typography.bodySmall,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
