@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import io.github.beacon.app.ui.components.BeaconUiMetrics
import io.github.beacon.app.ui.components.SettingsFieldGroup
import io.github.beacon.app.ui.components.SettingsGroup
import io.github.beacon.app.ui.components.SettingsPreferences
import io.github.beacon.app.ui.components.SettingsRow
import io.github.beacon.app.ui.components.SettingsSection
import io.github.beacon.app.ui.components.SettingsOverviewCard
import io.github.beacon.app.ui.components.SettingsOverviewField
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
    onSelectReceiveMode: (ReceiveMode) -> Unit,
    onOperationDetails: (String) -> Unit,
    busy: Boolean,
    polling: PollingState,
) {
    item {
        val ready = runtimeState.runtimeConnected && runtimeState.receivePlanLoaded &&
            runtimeState.configuredEndpoint.isNotBlank() && polling.config.enabled
        val failed = polling.blocked.isNotBlank() ||
            (polling.lastOutcome != null && polling.lastOutcome !in setOf(
                io.github.beacon.app.domain.model.SyncOutcome.OK,
                io.github.beacon.app.domain.model.SyncOutcome.STOPPED,
                io.github.beacon.app.domain.model.SyncOutcome.BUSY)) ||
            (receivePlan.mode == ReceiveMode.REALTIME && runtimeState.realtimeDiagnostics.failure !=
                io.github.beacon.app.domain.model.RealtimeFailure.NONE)
        val healthy = ready && !failed &&
            ((receivePlan.mode == ReceiveMode.POWER_SAVING &&
                polling.lastOutcome == io.github.beacon.app.domain.model.SyncOutcome.OK) || runtimeState.realtimeRunning ||
                (receivePlan.mode == ReceiveMode.RULES &&
                    polling.lastOutcome == io.github.beacon.app.domain.model.SyncOutcome.OK &&
                    runtimeState.realtimeDiagnostics.failure == io.github.beacon.app.domain.model.RealtimeFailure.NONE))
        SettingsOverviewCard(
            title = stringResource(R.string.settings_receive_status_title),
            status = stringResource(when {
                !runtimeState.runtimeConnected -> R.string.settings_runtime_disconnected
                !runtimeState.receivePlanLoaded -> R.string.settings_data_loading
                runtimeState.transportBusy -> R.string.reception_syncing
                failed -> R.string.overview_attention
                healthy -> R.string.overview_running
                !polling.config.enabled -> R.string.reception_paused
                else -> R.string.overview_waiting
            }),
            subtitle = stringResource(receiveModeTitle(receivePlan.mode)),
            healthy = healthy && !runtimeState.transportBusy,
            error = ready && failed,
        ) {
            SettingsOverviewField(
                stringResource(R.string.reception_https_status),
                when {
                    runtimeState.configuredEndpoint.isBlank() ->
                        stringResource(R.string.settings_receive_unbound)
                    !runtimeState.runtimeConnected ->
                        stringResource(R.string.settings_runtime_disconnected)
                    !runtimeState.receivePlanLoaded ->
                        stringResource(R.string.settings_data_loading)
                    runtimeState.transportBusy -> stringResource(R.string.reception_syncing)
                    polling.blocked.isNotBlank() -> polling.blocked
                    !polling.config.enabled -> stringResource(R.string.reception_paused)
                    polling.lastOutcome == io.github.beacon.app.domain.model.SyncOutcome.AUTH ->
                        stringResource(R.string.reception_sync_auth_failure)
                    polling.lastOutcome == io.github.beacon.app.domain.model.SyncOutcome.TLS ->
                        stringResource(R.string.reception_sync_tls_failure)
                    polling.lastOutcome in setOf(io.github.beacon.app.domain.model.SyncOutcome.CONFIG,
                        io.github.beacon.app.domain.model.SyncOutcome.PROTOCOL) ->
                        stringResource(R.string.reception_sync_config_failure)
                    polling.lastOutcome == io.github.beacon.app.domain.model.SyncOutcome.TRANSIENT ->
                        stringResource(R.string.reception_sync_retry)
                    polling.lastOutcome == io.github.beacon.app.domain.model.SyncOutcome.STORAGE ->
                        stringResource(R.string.reception_storage_failure)
                    polling.lastOutcome == io.github.beacon.app.domain.model.SyncOutcome.OK ->
                        stringResource(R.string.reception_sync_ready)
                    else -> stringResource(R.string.reception_sync_waiting)
                },
                error = polling.blocked.isNotBlank() && runtimeState.runtimeConnected &&
                    runtimeState.configuredEndpoint.isNotBlank() && runtimeState.receivePlanLoaded &&
                    !runtimeState.transportBusy,
            )
            SettingsOverviewField(
                stringResource(R.string.receive_realtime_status_title),
                when {
                    runtimeState.configuredEndpoint.isBlank() ->
                        stringResource(R.string.settings_receive_unbound)
                    !runtimeState.runtimeConnected ->
                        stringResource(R.string.settings_runtime_disconnected)
                    !runtimeState.receivePlanLoaded -> stringResource(R.string.settings_data_loading)
                    receivePlan.mode == ReceiveMode.POWER_SAVING -> stringResource(R.string.reception_realtime_not_needed)
                    polling.blocked.isNotBlank() -> stringResource(R.string.reception_sync_blocked)
                    !polling.config.enabled -> stringResource(R.string.reception_paused)
                    runtimeState.realtimeRunning ->
                        stringResource(R.string.settings_realtime_connected)
                    receivePlan.mode == ReceiveMode.RULES -> runtimeState.realtimeStatus
                    runtimeState.realtimeDiagnostics.failure !=
                        io.github.beacon.app.domain.model.RealtimeFailure.NONE ->
                        stringResource(
                            when (runtimeState.realtimeDiagnostics.failure) {
                                io.github.beacon.app.domain.model.RealtimeFailure.AUTH ->
                                    R.string.settings_realtime_auth_failure
                                io.github.beacon.app.domain.model.RealtimeFailure.CONFIG ->
                                    R.string.settings_realtime_config_failure
                                io.github.beacon.app.domain.model.RealtimeFailure
                                    .CERTIFICATE_EXPIRED,
                                io.github.beacon.app.domain.model.RealtimeFailure
                                    .CERTIFICATE_NOT_YET_VALID,
                                io.github.beacon.app.domain.model.RealtimeFailure
                                    .CERTIFICATE_UNTRUSTED,
                                io.github.beacon.app.domain.model.RealtimeFailure.HOSTNAME ->
                                    R.string.settings_realtime_tls_failure
                                else -> R.string.settings_realtime_network_failure
                            }
                        )
                    else ->
                        runtimeState.realtimeStatus
                            .removePrefix(stringResource(R.string.receive_mode_realtime))
                            .lineSequence()
                            .first()
                },
            )
            SettingsOverviewField(
                stringResource(R.string.settings_last_sync_success_desc) +
                    (polling.lastSuccessInserted?.let {
                        " · " + stringResource(R.string.receive_success_count, it)
                    } ?: ""),
                if (polling.lastSuccessMs > 0) formatMessageTime(polling.lastSuccessMs)
                else stringResource(R.string.settings_sync_never),
            )
            io.github.beacon.app.ui.components.SettingsActions {
                BeaconButton(onClick = { actions.transport() }, modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !runtimeState.transportBusy && runtimeState.runtimeConnected) {
                    io.github.beacon.app.ui.components.BeaconLoadingIcon(
                            io.github.beacon.app.ui.icons.BeaconIcons.Sync, runtimeState.transportBusy,
                            Modifier.size(BeaconUiMetrics.IconInline), tint = LocalContentColor.current)
                    Spacer(Modifier.width(BeaconUiMetrics.Gap6))
                    BeaconText(stringResource(R.string.action_sync_now))
                }
            }
        }
    }
    item {
        ReceiveModeOptions(
            selected = receivePlan.mode,
            enabled = runtimeState.runtimeConnected && runtimeState.receivePlanLoaded && !busy,
            onSelect = onSelectReceiveMode,
        )
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
private fun ConnectionPreferences(
    runtimeState: RuntimeState,
    receptionForm: ReceptionFormState,
    busy: Boolean,
    grouped: Boolean = true,
) {
    ReceptionPreferences(stringResource(R.string.receive_heartbeat_title), grouped) {
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
                    if (receptionForm.plan.adaptiveHeartbeat) listOf(stringResource(R.string.settings_auto_adjusting))
                    else RealtimeHeartbeat.options.map {
                        stringResource(R.string.receive_heartbeat_seconds, it)
                    },
                    if (receptionForm.plan.adaptiveHeartbeat) 0 else RealtimeHeartbeat.options.indexOf(receptionForm.plan.heartbeatSeconds),
                    !busy &&
                        runtimeState.runtimeConnected &&
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
    grouped: Boolean = true,
) {
    ReceptionPreferences(stringResource(R.string.ui_v2_power_conditions_title), grouped) {
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
    var expanded by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SettingsGroupGap)) {
        io.github.beacon.app.ui.components.SettingsHint(stringResource(R.string.settings_inherited_receive_hint))
        SettingsGroup(stringResource(R.string.settings_inherited_receive_title)) {
            SettingsRow(stringResource(R.string.receive_mode_realtime),
                if (receptionForm.plan.adaptiveHeartbeat) stringResource(R.string.receive_adaptive_heartbeat)
                else stringResource(R.string.receive_heartbeat_seconds, receptionForm.plan.heartbeatSeconds),
                io.github.beacon.app.ui.icons.BeaconIcons.Realtime,
                iconTint = MaterialTheme.colorScheme.onSurface,
                onClick = { expanded = "connection" }, selection = true,
                enabled = !busy && runtimeState.runtimeConnected && runtimeState.receivePlanLoaded)
            SettingsRow(stringResource(R.string.ui_v2_power_conditions_title),
                stringResource(R.string.settings_interval_minutes_option, receptionForm.interval),
                io.github.beacon.app.ui.icons.BeaconIcons.PowerSaving,
                iconTint = MaterialTheme.colorScheme.onSurface,
                onClick = { expanded = "polling" }, selection = true,
                enabled = !busy && runtimeState.runtimeConnected && runtimeState.receivePlanLoaded)
        }
    }
    if (expanded != null) io.github.beacon.app.ui.components.BeaconAlertDialog(
        onDismissRequest = { expanded = null },
        title = { BeaconText(stringResource(if (expanded == "connection")
            R.string.receive_mode_realtime else R.string.ui_v2_power_conditions_title)) },
        text = {
            if (expanded == "connection") ConnectionPreferences(runtimeState, receptionForm, busy, grouped = false)
            else PollingPreferences(runtimeState, receptionForm, busy, grouped = false)
        },
        confirmButton = {
            BeaconButton({ expanded = null }) { BeaconText(stringResource(R.string.action_close)) }
        },
    )
}

@androidx.compose.runtime.Composable
private fun ReceptionPreferences(
    title: String,
    grouped: Boolean,
    content: @androidx.compose.runtime.Composable ColumnScope.() -> Unit,
) {
    if (grouped) SettingsPreferences(title, content)
    else
        Column(
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = io.github.beacon.app.ui.components.BeaconUiMetrics.CardInset,
                    vertical = 4.dp,
                ),
            content = content,
        )
}