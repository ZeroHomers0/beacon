package io.github.beacon.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.OperationKind
import io.github.beacon.app.domain.model.OperationStatus

/**
 * Display strings derived from state rather than stored in it.
 *
 * They are composable because every branch resolves a string resource, so they cannot live on the
 * domain models without dragging Android resources into [io.github.beacon.app.domain].
 */
@Composable
fun formatMessageTime(time: Long): String =
    if (time <= 0) stringResource(R.string.ui_unknown_label)
    else io.github.beacon.app.domain.policy.DisplayTime.compact(time)

/** Connection remediation is fixed text; everything else shows the runtime's own message. */
@Composable
fun userOperationFeedback(status: OperationStatus): String =
    if (status.kind == OperationKind.CONNECTION_LOST)
        stringResource(R.string.ui_local_data_attention_hint)
    else status.text

@Composable
fun connectionStatusLabel(state: io.github.beacon.app.domain.model.RuntimeState): String =
    stringResource(
        when {
            state.serverChecking -> R.string.connection_status_checking
            state.configuredEndpoint.isBlank() -> R.string.connection_status_unconfigured
            state.serverReachable == false -> R.string.connection_status_failed
            state.serverReachable == null -> R.string.connection_status_unchecked
            state.receivePlan.mode == io.github.beacon.app.domain.model.ReceiveMode.REALTIME &&
                state.pollingState.config.enabled &&
                !state.realtimeRunning -> R.string.connection_status_waiting
            else -> R.string.connection_status_connected
        }
    )

fun formatStorageSize(
    context: android.content.Context,
    bytes: Long,
    short: Boolean = false,
): String =
    (if (short) android.text.format.Formatter.formatShortFileSize(context, bytes)
        else android.text.format.Formatter.formatFileSize(context, bytes))
        .replace("kB", "KB")
