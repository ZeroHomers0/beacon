package io.github.beacon.app.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.MessageAction
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.feature.settings.ReceiveModeDialog
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconAlertDialog
import io.github.beacon.app.ui.components.BeaconButton
import io.github.beacon.app.ui.components.BeaconConfirmDialog
import io.github.beacon.app.ui.components.BeaconOutlinedButton
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconTextButton
import io.github.beacon.app.ui.components.BeaconUiMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The modal dialogs that can appear over any page.
 *
 * They are pure renderings of state (`operationDetails`, `pendingNavigation`, `receiveModesOpen`,
 * `pendingDelete`) with no local memory of their own, so extracting them costs nothing beyond the
 * parameters below — and it takes the four largest inline blocks out of [BeaconApp].
 */
@Composable
internal fun BeaconDialogHost(
    ui: BeaconUiState,
    runtimeState: RuntimeState,
    actions: RuntimeActions,
    messageScroll: LazyListState,
    uiScope: CoroutineScope,
) {
    ui.operationDetails?.let { details ->
        BeaconAlertDialog(
            onDismissRequest = { ui.operationDetails = null },
            title = { BeaconText(stringResource(R.string.ui_operation_result_title)) },
            text = { BeaconText(details) },
            confirmButton = {
                BeaconButton(onClick = { ui.operationDetails = null }) {
                    BeaconText(stringResource(R.string.ui_got_it_action))
                }
            },
            dismissButton = {
                BeaconTextButton(onClick = { ui.operationDetails = null }) {
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
    if (ui.receiveModesOpen)
        ReceiveModeDialog(
            ui.receivePlan.mode,
            onSelect = selectMode@{ mode ->
                    if (
                        !runtimeState.receivePlanLoaded ||
                            !runtimeState.runtimeConnected ||
                            runtimeState.messageBusy ||
                            ui.savingSettings
                    )
                        return@selectMode
                    if (mode != ui.receivePlan.mode) {
                        val changeMode = {
                            ui.receptionForm.reset(
                                runtimeState.pollingState.config,
                                runtimeState.receivePlan.copy(mode = mode),
                            )
                            actions.receivePlan(ui.receivePlan.copy(mode = mode))
                        }
                        if (
                            ui.receptionForm.edited &&
                                ui.receptionForm.differsFrom(
                                    runtimeState.pollingState.config,
                                    runtimeState.receivePlan,
                                )
                        )
                            ui.pendingNavigation = changeMode
                        else changeMode()
                    }
                    ui.receiveModesOpen = false
                },
            onDismiss = { ui.receiveModesOpen = false },
        )

    ui.pendingDelete?.let { keys ->
        BeaconConfirmDialog(
            title = stringResource(R.string.msg_delete_confirm_title, keys.size),
            description =
                if (keys.size == 1) stringResource(R.string.ui_delete_undo_hint)
                else stringResource(R.string.ui_delete_multi_warning_hint),
            confirmText = stringResource(R.string.ui_confirm_delete_action),
            danger = false,
            enabled = !runtimeState.messageBusy,
            onDismiss = { ui.pendingDelete = null },
            onConfirm = {
                ui.pendingDelete = null
                actions.manageMessages(
                    MessageAction.DELETE,
                    keys,
                    onComplete = {
                        ui.selectedKeys = emptyList()
                        uiScope.launch { messageScroll.scrollToItem(0) }
                    },
                )
            },
        )
    }
}

