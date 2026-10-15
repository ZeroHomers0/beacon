package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.policy.DisplayTime

@Composable
internal fun MessageCardFooter(
    message: IncomingMessage,
    busy: Boolean,
    selectionMode: Boolean,
    onFilterApplication: (() -> Unit)?,
    onFilterGroup: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BeaconText(
                if (message.createdAtMs <= 0) stringResource(R.string.ui_unknown_label) else DisplayTime.clock(message.createdAtMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (message.reminderPending) BeaconText(
                stringResource(R.string.msg_reminder_pending_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (message.applicationId.isNotBlank()) MessageFilterTag(
                label = message.applicationName.ifBlank { stringResource(R.string.message_application_short, message.applicationId.take(8)) },
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 88.dp),
                compact = true,
                enabled = !busy && !selectionMode && onFilterApplication != null,
                onClick = { onFilterApplication?.invoke() },
            )
            if (message.group.isNotBlank()) MessageFilterTag(
                label = message.group,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 88.dp),
                compact = true,
                enabled = !busy && !selectionMode && onFilterGroup != null,
                onClick = { onFilterGroup?.invoke() },
            )
        }
    }
}
