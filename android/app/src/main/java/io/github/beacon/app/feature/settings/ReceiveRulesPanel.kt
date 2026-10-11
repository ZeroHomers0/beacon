package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.ReceivePlan
import io.github.beacon.app.ui.components.*

@Composable
internal fun ReceiveRulesPanel(saved: ReceivePlan, busy: Boolean, onSave: (ReceivePlan) -> Unit) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    var draft by remember(saved) { mutableStateOf(saved) }
    var start by remember(saved) { mutableStateOf(formatMinute(saved.scheduleStart)) }
    var end by remember(saved) { mutableStateOf(formatMinute(saved.scheduleEnd)) }
    val first = parseMinute(start)
    val last = parseMinute(end)
    val valid = !draft.scheduleEnabled || first != null && last != null && first != last
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiSection(stringResource(R.string.settings_rules_priority)) {
            resources
                .getString(R.string.settings_rules_priority_order)
                .split(" → ")
                .forEachIndexed { index, rule -> DiagnosticRow("${index + 1}", rule) }
            SettingsHint(resources.getString(R.string.settings_rules_priority_hint))
        }
        UiSection(stringResource(R.string.settings_rules_battery)) {
            BeaconPreferenceToggle(
                resources.getString(R.string.settings_rules_battery_toggle),
                resources.getString(R.string.settings_rules_battery_hint),
                draft.lowBatteryEnabled,
                !busy,
            ) {
                draft = draft.copy(lowBatteryEnabled = it)
            }
            if (draft.lowBatteryEnabled) {
                val levels =
                    (listOf(10, 15, 20, 25, 30, 40, 50) + draft.lowBatteryPercent)
                        .distinct()
                        .sorted()
                BeaconDropdownPreference(
                    resources.getString(R.string.settings_rules_battery_threshold),
                    levels.map { resources.getString(R.string.settings_rules_battery_percent, it) },
                    levels.indexOf(draft.lowBatteryPercent),
                    !busy,
                ) {
                    draft = draft.copy(lowBatteryPercent = levels[it])
                }
            }
        }
        UiSection(stringResource(R.string.settings_rules_schedule)) {
            BeaconPreferenceToggle(
                resources.getString(R.string.settings_rules_schedule_toggle),
                resources.getString(R.string.settings_rules_schedule_hint),
                draft.scheduleEnabled,
                !busy,
            ) {
                draft = draft.copy(scheduleEnabled = it)
            }
            if (draft.scheduleEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BeaconTextField(
                        start,
                        { start = it.take(5) },
                        Modifier.weight(1f),
                        label = { BeaconText(stringResource(R.string.settings_rules_start)) },
                        labelText = resources.getString(R.string.settings_rules_start),
                        singleLine = true,
                        isError = first == null,
                        enabled = !busy,
                    )
                    BeaconTextField(
                        end,
                        { end = it.take(5) },
                        Modifier.weight(1f),
                        label = { BeaconText(stringResource(R.string.settings_rules_end)) },
                        labelText = resources.getString(R.string.settings_rules_end),
                        singleLine = true,
                        isError = last == null || first == last,
                        enabled = !busy,
                    )
                }
                SettingsHint(resources.getString(R.string.settings_rules_time_format))
                BeaconDropdownPreference(
                    resources.getString(R.string.settings_rules_schedule_mode),
                    listOf(
                        resources.getString(R.string.receive_mode_realtime),
                        resources.getString(R.string.ui_power_saving_mode),
                    ),
                    if (draft.scheduleRealtime) 0 else 1,
                    !busy,
                ) {
                    draft = draft.copy(scheduleRealtime = it == 0)
                }
            }
        }
        UiSection(stringResource(R.string.settings_rules_other_times)) {
            BeaconPreferenceToggle(
                resources.getString(R.string.receive_rule_charging),
                "",
                draft.realtimeWhileCharging,
                !busy,
            ) {
                draft = draft.copy(realtimeWhileCharging = it)
            }
            BeaconPreferenceToggle(
                resources.getString(R.string.receive_rule_wifi),
                resources.getString(R.string.settings_rules_wifi_hint),
                draft.realtimeOnWifi,
                !busy,
            ) {
                draft = draft.copy(realtimeOnWifi = it)
            }
            SettingsHint(resources.getString(R.string.settings_rules_fallback_hint))
        }
        val value =
            draft.copy(
                scheduleStart = first ?: draft.scheduleStart,
                scheduleEnd = last ?: draft.scheduleEnd,
            )
        BeaconActionPair(
            dismiss = {
                BeaconOutlinedButton(
                    onClick = {
                        draft = saved
                        start = formatMinute(saved.scheduleStart)
                        end = formatMinute(saved.scheduleEnd)
                    },
                    enabled = !busy && (value != saved || !valid),
                ) {
                    BeaconText(stringResource(R.string.action_cancel))
                }
            },
            confirm = {
                BeaconButton(
                    onClick = { onSave(value) },
                    enabled = !busy && valid && value != saved,
                ) {
                    BeaconText(stringResource(R.string.settings_rules_save))
                }
            },
        )
    }
}
