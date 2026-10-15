package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.ui.components.*

/**
 * Rule conditions only. The page keeps a single draft and a single save button, so this panel edits
 * [form] in place instead of owning its own confirmation row.
 */
@Composable
internal fun ReceiveRulesPanel(form: ReceptionFormState, busy: Boolean) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val plan = form.plan
    val start = form.scheduleStartText
    val end = form.scheduleEndText
    UiSection(stringResource(R.string.settings_rules_conditions)) {
        SettingsHint(resources.getString(R.string.settings_rules_priority_order))
        Column {
            BeaconPreferenceToggle(
                resources.getString(R.string.settings_rules_battery),
                "",
                plan.lowBatteryEnabled,
                !busy,
            ) {
                form.plan = plan.copy(lowBatteryEnabled = it)
                form.edited = true
            }
            if (plan.lowBatteryEnabled) {
                SettingsFieldDivider()
                val levels =
                    (listOf(10, 15, 20, 25, 30, 40, 50) + plan.lowBatteryPercent)
                        .distinct()
                        .sorted()
                BeaconDropdownPreference(
                    resources.getString(R.string.settings_rules_battery_threshold),
                    levels.map { resources.getString(R.string.settings_rules_battery_percent, it) },
                    levels.indexOf(plan.lowBatteryPercent),
                    !busy,
                ) {
                    form.plan = plan.copy(lowBatteryPercent = levels[it])
                    form.edited = true
                }
            }
            SettingsFieldDivider()

            BeaconPreferenceToggle(
                resources.getString(R.string.settings_rules_schedule),
                "",
                plan.scheduleEnabled,
                !busy,
            ) {
                form.plan = plan.copy(scheduleEnabled = it)
                form.edited = true
            }
            if (plan.scheduleEnabled) {
                SettingsFieldDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BeaconTimePreference(
                        start,
                        {
                            form.scheduleStartText = it
                            form.edited = true
                        },
                        stringResource(R.string.settings_rules_start),
                        Modifier.weight(1f),
                        enabled = !busy,
                    )
                    BeaconTimePreference(
                        end,
                        {
                            form.scheduleEndText = it
                            form.edited = true
                        },
                        stringResource(R.string.settings_rules_end),
                        Modifier.weight(1f),
                        enabled = !busy,
                    )
                }
                if (!form.scheduleValid)
                    BeaconText(
                        stringResource(R.string.settings_rules_time_invalid),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    )
                SettingsFieldDivider()
                BeaconDropdownPreference(
                    resources.getString(R.string.settings_rules_schedule_mode),
                    listOf(
                        resources.getString(R.string.receive_mode_realtime),
                        resources.getString(R.string.ui_power_saving_mode),
                    ),
                    if (plan.scheduleRealtime) 0 else 1,
                    !busy,
                ) {
                    form.plan = plan.copy(scheduleRealtime = it == 0)
                    form.edited = true
                }
            }
            SettingsFieldDivider()
            BeaconText(
                stringResource(R.string.settings_rules_other_times),
                Modifier.padding(vertical = 12.dp),
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            )
            Column {
                SettingsFieldGroup(
                    {
                        BeaconPreferenceToggle(
                            resources.getString(R.string.receive_rule_charging),
                            "",
                            plan.realtimeWhileCharging,
                            !busy,
                        ) {
                            form.plan = plan.copy(realtimeWhileCharging = it)
                            form.edited = true
                        }
                    },
                    {
                        BeaconPreferenceToggle(
                            resources.getString(R.string.receive_rule_wifi),
                            "",
                            plan.realtimeOnWifi,
                            !busy,
                        ) {
                            form.plan = plan.copy(realtimeOnWifi = it)
                            form.edited = true
                        }
                    },
                )
            }
        }
    }
}
