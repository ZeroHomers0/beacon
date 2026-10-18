package io.github.beacon.app.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult

/**
 * Shows one message at a time. A save and a failure can land together, and queueing them made the
 * reader watch a stale line first; a newer message now retires the older one. Messages carry a
 * dismiss affordance unless they also offer an action, so a toast never blocks the controls under it.
 */
suspend fun SnackbarHostState.showFeedback(
    message: String,
    actionLabel: String? = null,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(
        message = message,
        actionLabel = actionLabel,
        withDismissAction = actionLabel == null,
        duration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
    )
}

/**
 * Same one-at-a-time rule for the status line, which controls its own lifetime so the message stays
 * up long enough for a screen reader to finish it.
 */
suspend fun SnackbarHostState.showFeedback(
    message: String,
    duration: SnackbarDuration,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(message = message, withDismissAction = true, duration = duration)
}
