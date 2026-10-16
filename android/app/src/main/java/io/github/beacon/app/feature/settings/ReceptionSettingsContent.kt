@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
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
import io.github.beacon.app.ui.components.BeaconButton
import io.github.beacon.app.ui.components.BeaconDropdownPreference
import io.github.beacon.app.ui.components.BeaconPreferenceToggle
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.SettingsFieldGroup
import io.github.beacon.app.ui.components.SettingsGroup
import io.github.beacon.app.ui.components.SettingsPreferences
import io.github.beacon.app.ui.components.SettingsRow
import io.github.beacon.app.ui.components.SettingsSection
import io.github.beacon.app.ui.components.formatMessageTime
import io.github.beacon.app.ui.components.receiveModeIcon
import io.github.beacon.app.ui.components.receiveModeTitle

/**
 * The reception page edits one draft, saved through its toolbar action.
 *
 * Mode changes require confirmation before an unsaved draft can be discarded.
 */
internal fun LazyListScope.receptionSettingsItems(
    runtimeState: RuntimeState,
    actions: RuntimeActions,
    receivePlan: ReceivePlan,
    receptionForm: ReceptionFormState,
    onShowReceiveModes: () -> Unit,
    onOperationDetails: (String) -> Unit,
    busy: Boolean,
    polling: PollingState,
) {
    item {
        SettingsGroup(stringResource(R.string.settings_receive_mode_section)) {
            SettingsRow(
                stringResource(R.string.ui_current_receive_mode),
                stringResource(receiveModeTitle(receivePlan.mode)),
                receiveModeIcon(receivePlan.mode),
                iconTint = io.github.beacon.app.ui.components.receiveModeColor(receivePlan.mode),
                onClick = onShowReceiveModes,
            )
        }
    }
    if (runtimeState.runtimeConnected && !runtimeState.receivePlanLoaded)
        item {
            SettingsSection(stringResource(R.string.settings_receive_loading_title)) {
                io.github.beacon.app.ui.components.SettingsHint(
                    stringResource(
                        if (busy) R.string.settings_data_loading
                        else R.string.settings_receive_retry_hint
                    )
                )
                io.github.beacon.app.ui.components.BeaconOutlinedButton(
                    onClick = { actions.refreshPolling { actions.receivePlan() } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BeaconText(stringResource(R.string.action_retry))
                }
            }
        }
    item {
        SettingsPreferences(stringResource(R.string.settings_receive_status_title)) {
            ReceptionStatusRow(
                stringResource(
                    if (receivePlan.mode == ReceiveMode.POWER_SAVING)
                        R.string.settings_auto_receive_title
                    else R.string.receive_realtime_status_title
                ),
                when {
                    runtimeState.configuredEndpoint.isBlank() ->
                        stringResource(R.string.settings_receive_unbound)
                    !runtimeState.runtimeConnected ->
                        stringResource(R.string.settings_runtime_disconnected)
                    polling.blocked.isNotBlank() -> polling.blocked
                    !polling.config.enabled || receivePlan.mode == ReceiveMode.POWER_SAVING ->
                        io.github.beacon.app.ui.components.receiveLabel(polling)
                    else ->
                        runtimeState.realtimeStatus.removePrefix(
                            stringResource(R.string.receive_mode_realtime)
                        )
                },
                error = polling.blocked.isNotBlank(),
            )
            io.github.beacon.app.ui.components.SettingsFieldDivider()
            ReceptionStatusRow(
                stringResource(R.string.settings_last_sync_success_desc) +
                    (polling.lastSuccessInserted?.let {
                        " · " + stringResource(R.string.receive_success_count, it)
                    } ?: ""),
                formatMessageTime(polling.lastSuccessMs),
            )
            Column(Modifier.padding(vertical = 12.dp)) {
                io.github.beacon.app.ui.components.BeaconActionPair(
                    dismiss = {
                        io.github.beacon.app.ui.components.BeaconOutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && runtimeState.runtimeConnected,
                            onClick = {
                                actions.refreshPolling {
                                    actions.receivePlan()
                                }
                            },
                        ) {
                            BeaconText(stringResource(R.string.action_refresh_status))
                        }
                    },
                    confirm = {
                        BeaconButton(
                            onClick = { actions.transport() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && runtimeState.runtimeConnected,
                        ) {
                            BeaconText(stringResource(R.string.action_sync_now))
                        }
                    },
                )
            }
        }
    }
    if (receivePlan.mode == ReceiveMode.RULES) {
        item {
            ReceiveRulesPanel(
                receptionForm,
                busy || !runtimeState.runtimeConnected || !runtimeState.receivePlanLoaded,
            )
        }
    }
    if (receivePlan.mode == ReceiveMode.REALTIME) {
        item { ConnectionPreferences(runtimeState, receptionForm, busy) }
    }
    if (receivePlan.mode == ReceiveMode.POWER_SAVING) {
        item { PollingPreferences(runtimeState, receptionForm, busy) }
    }
    if (receivePlan.mode == ReceiveMode.RULES) {
        item { InheritedReceptionPreferences(runtimeState, receptionForm, busy) }
    }
}

@androidx.compose.runtime.Composable
private fun ReceptionStatusRow(title: String, details: String, error: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalArrangement =
            Arrangement.spacedBy(4.dp, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        BeaconText(title, style = MaterialTheme.typography.bodyMedium)
        BeaconText(
            details,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@androidx.compose.runtime.Composable
private fun ConnectionPreferences(
    runtimeState: RuntimeState,
    receptionForm: ReceptionFormState,
    busy: Boolean,
) {
    SettingsPreferences(stringResource(R.string.receive_heartbeat_title)) {
        SettingsFieldGroup(
            {
                BeaconPreferenceToggle(
                    stringResource(R.string.receive_adaptive_heartbeat),
                    "",
                    receptionForm.plan.adaptiveHeartbeat,
                    !busy && runtimeState.runtimeConnected && runtimeState.receivePlanLoaded,
                ) {
                    receptionForm.plan = receptionForm.plan.copy(adaptiveHeartbeat = it)
                    receptionForm.edited = true
                }
            },
            {
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
            },
            {
                BeaconPreferenceToggle(
                    stringResource(R.string.receive_batch_ordinary),
                    "",
                    receptionForm.plan.batchOrdinaryMessages,
                    !busy && runtimeState.runtimeConnected && runtimeState.receivePlanLoaded,
                ) {
                    receptionForm.plan = receptionForm.plan.copy(batchOrdinaryMessages = it)
                    receptionForm.edited = true
                }
            },
        )
    }
}

@androidx.compose.runtime.Composable
private fun PollingPreferences(
    runtimeState: RuntimeState,
    receptionForm: ReceptionFormState,
    busy: Boolean,
) {
    SettingsPreferences(stringResource(R.string.ui_v2_power_conditions_title)) {
        val interval = receptionForm.interval
        val unmetered = receptionForm.unmetered
        val charging = receptionForm.charging
        val intervals = (listOf(15, 30, 60, 120) + interval).distinct().sorted()
        Column {
            SettingsFieldGroup(
                {
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
                        !busy && runtimeState.runtimeConnected && runtimeState.receivePlanLoaded,
                    ) {
                        receptionForm.interval = intervals[it]
                        receptionForm.edited = true
                    }
                },
                {
                    BeaconPreferenceToggle(
                        stringResource(R.string.receive_unmetered_title),
                        "",
                        unmetered,
                        !busy && runtimeState.runtimeConnected && runtimeState.receivePlanLoaded,
                    ) {
                        receptionForm.unmetered = it
                        receptionForm.edited = true
                    }
                },
                {
                    BeaconPreferenceToggle(
                        stringResource(R.string.receive_charging_title),
                        "",
                        charging,
                        !busy && runtimeState.runtimeConnected && runtimeState.receivePlanLoaded,
                    ) {
                        receptionForm.charging = it
                        receptionForm.edited = true
                    }
                },
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun InheritedReceptionPreferences(
    runtimeState: RuntimeState,
    receptionForm: ReceptionFormState,
    busy: Boolean,
) {
    var connectionOpen by
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var pollingOpen by
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        verticalArrangement =
            Arrangement.spacedBy(
                io.github.beacon.app.ui.components.BeaconUiMetrics.SettingsGroupGap
            )
    ) {
        SettingsPreferences(stringResource(R.string.settings_inherited_receive_title)) {
            io.github.beacon.app.ui.components.SettingsHint(
                stringResource(R.string.settings_inherited_receive_hint)
            )
            io.github.beacon.app.ui.components.SettingsActionRow(
                stringResource(R.string.receive_mode_realtime),
                if (receptionForm.plan.adaptiveHeartbeat)
                    stringResource(R.string.receive_adaptive_heartbeat)
                else
                    stringResource(
                        R.string.receive_heartbeat_seconds,
                        receptionForm.plan.heartbeatSeconds,
                    ),
                io.github.beacon.app.ui.icons.BeaconIcons.Realtime,
                !busy && runtimeState.runtimeConnected && runtimeState.receivePlanLoaded,
            ) {
                connectionOpen = !connectionOpen
            }
            io.github.beacon.app.ui.components.SettingsFieldDivider()
            io.github.beacon.app.ui.components.SettingsActionRow(
                stringResource(receiveModeTitle(ReceiveMode.POWER_SAVING)),
                stringResource(R.string.settings_interval_minutes_option, receptionForm.interval),
                io.github.beacon.app.ui.components.receiveModeIcon(ReceiveMode.POWER_SAVING),
                !busy && runtimeState.runtimeConnected && runtimeState.receivePlanLoaded,
            ) {
                pollingOpen = !pollingOpen
            }
        }
        if (connectionOpen) ConnectionPreferences(runtimeState, receptionForm, busy)
        if (pollingOpen) PollingPreferences(runtimeState, receptionForm, busy)
    }
}
