@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.PollingState
import io.github.beacon.app.domain.model.RealtimeHeartbeat
import io.github.beacon.app.domain.model.ReceiveMode
import io.github.beacon.app.domain.model.ReceivePlan
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconActionButton
import io.github.beacon.app.ui.components.BeaconButton
import io.github.beacon.app.ui.components.BeaconDropdownPreference
import io.github.beacon.app.ui.components.BeaconPreferenceToggle
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.DiagnosticRow
import io.github.beacon.app.ui.components.OperationResultPanel
import io.github.beacon.app.ui.components.SettingsGroup
import io.github.beacon.app.ui.components.SettingsHint
import io.github.beacon.app.ui.components.SettingsRow
import io.github.beacon.app.ui.components.UiSection
import io.github.beacon.app.ui.components.formatMessageTime
import io.github.beacon.app.ui.components.receiveLabel
import io.github.beacon.app.ui.components.receiveModeIcon
import io.github.beacon.app.ui.components.receiveModeTitle
import io.github.beacon.app.ui.icons.BeaconIcons

/**
 * The reception page edits one draft, saved through its toolbar action.
 *
 * The mode row writes through immediately; switching modes resets the previous draft.
 */
internal fun LazyListScope.receptionSettingsItems(
    runtimeState: RuntimeState,
    actions: RuntimeActions,
    receivePlan: ReceivePlan,
    receptionForm: ReceptionFormState,
    onReceivePlanChange: (ReceivePlan) -> Unit,
    onShowReceiveModes: () -> Unit,
    onOperationDetails: (String) -> Unit,
    busy: Boolean,
    polling: PollingState,
) {
    item {
        SettingsGroup(stringResource(R.string.settings_reception_page_title)) {
            SettingsRow(
                stringResource(R.string.ui_current_receive_mode),
                stringResource(receiveModeTitle(receivePlan.mode)),
                receiveModeIcon(receivePlan.mode),
                iconTint = io.github.beacon.app.ui.components.receiveModeColor(receivePlan.mode),
                onClick = onShowReceiveModes,
            )
        }
    }
    item {
        UiSection(stringResource(R.string.settings_receive_status_title)) {
            BeaconPreferenceToggle(
                stringResource(R.string.settings_battery_saver_title),
                receiveLabel(
                    polling.copy(config = polling.config.copy(enabled = receptionForm.enabled))
                ),
                receptionForm.enabled,
                !busy,
            ) {
                receptionForm.enabled = it
                receptionForm.edited = true
            }
            SettingsHint(stringResource(R.string.settings_receive_switch_hint))
            if (receivePlan.mode == ReceiveMode.RULES)
                SettingsHint(stringResource(R.string.receive_actual_fallback))
            if (polling.blocked.isNotBlank())
                BeaconText(polling.blocked, color = MaterialTheme.colorScheme.error)
            DiagnosticRow(
                stringResource(R.string.settings_last_sync_success_desc),
                formatMessageTime(polling.lastSuccessMs),
            )
            if (receivePlan.mode != ReceiveMode.POWER_SAVING)
                OperationResultPanel(
                    stringResource(R.string.receive_realtime_status_title),
                    runtimeState.realtimeStatus,
                )
            OperationResultPanel(
                stringResource(R.string.receive_latest_result_title),
                polling.lastResult,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BeaconActionButton(
                    stringResource(R.string.action_sync_now),
                    BeaconIcons.Sync,
                    Modifier.weight(1f),
                    !busy,
                ) {
                    actions.transport()
                }
                BeaconActionButton(
                    stringResource(R.string.action_refresh_status),
                    BeaconIcons.History,
                    Modifier.weight(1f),
                    !busy,
                    {
                        actions.refreshPolling { result ->
                            actions.receivePlan()
                            onOperationDetails(result)
                        }
                    },
                )
            }
        }
    }
    if (receivePlan.mode == ReceiveMode.RULES) {
        item { ReceiveRulesPanel(receptionForm, busy) }
    }
    if (receivePlan.mode != ReceiveMode.POWER_SAVING) {
        item {
            UiSection(stringResource(R.string.receive_heartbeat_title)) {
                BeaconPreferenceToggle(
                    stringResource(R.string.receive_adaptive_heartbeat),
                    stringResource(R.string.receive_adaptive_heartbeat_hint),
                    receptionForm.plan.adaptiveHeartbeat,
                    !busy && runtimeState.receivePlanLoaded,
                ) {
                    receptionForm.plan = receptionForm.plan.copy(adaptiveHeartbeat = it)
                    receptionForm.edited = true
                }
                BeaconDropdownPreference(
                    stringResource(R.string.receive_heartbeat_interval),
                    RealtimeHeartbeat.options.map {
                        stringResource(R.string.receive_heartbeat_seconds, it)
                    },
                    RealtimeHeartbeat.options.indexOf(receptionForm.plan.heartbeatSeconds),
                    !busy &&
                        runtimeState.receivePlanLoaded &&
                        !receptionForm.plan.adaptiveHeartbeat,
                ) {
                    receptionForm.plan =
                        receptionForm.plan.copy(heartbeatSeconds = RealtimeHeartbeat.options[it])
                    receptionForm.edited = true
                }
                SettingsHint(stringResource(R.string.receive_heartbeat_hint))
                BeaconPreferenceToggle(
                    stringResource(R.string.receive_batch_ordinary),
                    stringResource(R.string.receive_batch_ordinary_hint),
                    receptionForm.plan.batchOrdinaryMessages,
                    !busy && runtimeState.receivePlanLoaded,
                ) {
                    receptionForm.plan = receptionForm.plan.copy(batchOrdinaryMessages = it)
                    receptionForm.edited = true
                }
            }
        }
    }
    if (receivePlan.mode != ReceiveMode.REALTIME) {
        item {
            UiSection(
                stringResource(
                    if (receivePlan.mode == ReceiveMode.RULES) R.string.receive_fallback_title
                    else R.string.settings_auto_receive_title
                )
            ) {
                val interval = receptionForm.interval
                val unmetered = receptionForm.unmetered
                val charging = receptionForm.charging
                if (receivePlan.mode == ReceiveMode.RULES)
                    SettingsHint(stringResource(R.string.receive_fallback_hint))
                val intervals = (listOf(15, 30, 60, 120) + interval).distinct().sorted()
                BeaconDropdownPreference(
                    stringResource(R.string.receive_interval),
                    intervals.map {
                        if (it < 60)
                            stringResource(
                                R.string.settings_interval_minutes_option,
                                it,
                            )
                        else if (it % 60 == 0)
                            stringResource(
                                R.string.settings_interval_hours_option,
                                it / 60,
                            )
                        else
                            stringResource(
                                R.string.settings_interval_minutes_option,
                                it,
                            )
                    },
                    intervals.indexOf(interval),
                    !busy,
                ) {
                    receptionForm.interval = intervals[it]
                    receptionForm.edited = true
                }
                BeaconPreferenceToggle(
                    stringResource(R.string.receive_unmetered_title),
                    stringResource(R.string.settings_unmetered_hint),
                    unmetered,
                    !busy,
                ) {
                    receptionForm.unmetered = it
                    receptionForm.edited = true
                }
                BeaconPreferenceToggle(
                    stringResource(R.string.receive_charging_title),
                    stringResource(R.string.settings_charging_wait_hint),
                    charging,
                    !busy,
                ) {
                    receptionForm.charging = it
                    receptionForm.edited = true
                }
                SettingsHint(stringResource(R.string.settings_auto_receive_note_hint))
            }
        }
    }
}
