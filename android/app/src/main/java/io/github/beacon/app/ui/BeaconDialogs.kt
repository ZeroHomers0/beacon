package io.github.beacon.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.MessageAction
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconAlertDialog
import io.github.beacon.app.ui.components.BeaconButton
import io.github.beacon.app.ui.components.BeaconConfirmDialog
import io.github.beacon.app.ui.components.BeaconOutlinedButton
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconTextButton
import io.github.beacon.app.ui.components.BeaconUiMetrics

/**
 * The modal dialogs that can appear over any page.
 *
 * They are pure renderings of state (`operationDetails`, `pendingNavigation`,
 * `pendingDelete`) with no local memory of their own, so extracting them costs nothing beyond the
 * parameters below — and it takes the four largest inline blocks out of [BeaconApp].
 */
@Composable
internal fun BeaconDialogHost(
    ui: BeaconUiState,
    runtimeState: RuntimeState,
    actions: RuntimeActions,
) {
    ui.operationDetails?.let { details ->
        // One dismissal is enough: the pair used to read 「知道了 / 关闭」, two ways to say nothing.
        BeaconAlertDialog(
            onDismissRequest = { ui.operationDetails = null },
            title = { BeaconText(stringResource(R.string.ui_operation_result_title)) },
            text = { BeaconText(details) },
            confirmButton = {
                BeaconButton(onClick = { ui.operationDetails = null }) {
                    BeaconText(stringResource(R.string.action_close))
                }
            },
        )
    }

    if (ui.pendingNavigation != null)
        BeaconAlertDialog(
            onDismissRequest = { if (!ui.savingSettings) ui.pendingNavigation = null },
            title = { BeaconText(stringResource(R.string.settings_unsaved_title)) },
            text = { BeaconText(stringResource(R.string.settings_unsaved_hint)) },
            confirmButton = {
                BeaconButton(
                    onClick = { ui.pendingNavigation = null },
                    enabled = !ui.savingSettings,
                ) {
                    BeaconText(stringResource(R.string.settings_continue_editing))
                }
            },
            dismissButton = {
                BeaconOutlinedButton(
                    onClick = ui::discardAndContinue,
                    enabled = !ui.savingSettings,
                ) {
                    BeaconText(stringResource(R.string.settings_discard_changes))
                }
            },
        )
    ui.pendingDelete?.let { keys ->
        val single = keys.size == 1
        BeaconConfirmDialog(
            title =
                if (single) stringResource(R.string.msg_delete_confirm_single_title)
                else stringResource(R.string.msg_delete_confirm_title, keys.size),
            description =
                if (single) stringResource(R.string.ui_delete_undo_hint)
                else stringResource(R.string.ui_delete_multi_warning_hint),
            confirmText = stringResource(R.string.ui_confirm_delete_action),
            danger = true,
            enabled = !runtimeState.messageBusy,
            onDismiss = { ui.pendingDelete = null },
            onConfirm = {
                ui.pendingDelete = null
                actions.manageMessages(
                    MessageAction.DELETE,
                    keys,
                    onComplete = {
                        ui.selectedKeys = emptyList()
                    },
                )
            },
        )
    }
}

