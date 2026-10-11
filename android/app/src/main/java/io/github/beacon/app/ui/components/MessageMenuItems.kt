package io.github.beacon.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R

/** List cards and details use the same action order and read-state wording. */
@Composable
fun MessageMenuItems(
    isRead: Boolean,
    onToggleRead: () -> Unit,
    onCopy: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onShare: (() -> Unit)? = null,
    onRemind: (() -> Unit)? = null,
) {
    BeaconDropdownMenuItem(
        {
            BeaconText(
                stringResource(
                    if (isRead) R.string.action_mark_unread else R.string.action_mark_read
                )
            )
        },
        onToggleRead,
    )
    BeaconDropdownMenuItem({ BeaconText(stringResource(R.string.action_copy_title)) }) {
        onCopy(false)
    }
    BeaconDropdownMenuItem({ BeaconText(stringResource(R.string.action_copy_body)) }) {
        onCopy(true)
    }
    onShare?.let { action ->
        BeaconDropdownMenuItem({ BeaconText(stringResource(R.string.message_share)) }, action)
    }
    onRemind?.let { action ->
        BeaconDropdownMenuItem({ BeaconText(stringResource(R.string.message_remind)) }, action)
    }
    BeaconDropdownMenuItem({ BeaconText(stringResource(R.string.action_delete_message)) }, onDelete)
}
