package io.github.beacon.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.OperationKind
import io.github.beacon.app.domain.model.OperationStatus
import io.github.beacon.app.domain.model.PollingState

/**
 * Display strings derived from state rather than stored in it.
 *
 * They are composable because every branch resolves a string resource, so they cannot live on the
 * domain models without dragging Android resources into [io.github.beacon.app.domain].
 */
@Composable
fun formatMessageTime(time: Long): String =
    if (time <= 0) stringResource(R.string.ui_unknown_label)
    else
        java.text
            .SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(time))

@Composable
fun receiveLabel(state: PollingState): String =
    when {
        !state.config.enabled -> stringResource(R.string.ui_receive_paused_title)
        state.blocked.isNotBlank() -> stringResource(R.string.ui_receive_attention_title)
        else -> stringResource(R.string.ui_battery_saver_on_desc)
    }

/** Connection remediation is fixed text; everything else shows the runtime's own message. */
@Composable
fun userOperationFeedback(status: OperationStatus): String =
    if (status.kind == OperationKind.CONNECTION_LOST)
        stringResource(R.string.ui_local_data_attention_hint)
    else status.text
