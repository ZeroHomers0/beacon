package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import java.util.Locale

@Composable
internal fun QuietHoursPanel(
    state: RuntimeState,
    actions: RuntimeActions,
    form: QuietHoursFormState = remember { QuietHoursFormState() },
    scheduleOnly: Boolean = false,
    onSchedule: (() -> Unit)? = null,
) {
    with(form) {
        LaunchedEffect(state.runtimeConnected, attempt) {
            if (!state.runtimeConnected || loaded) return@LaunchedEffect
            loaded = false
            saving = false
            failed = false
            if (state.runtimeConnected)
                actions.quietHours(null, onFailure = { failed = true }) {
                    reset(it)
                    loaded = true
                }
        }
        val editable = loaded && !saving && state.runtimeConnected
        val labels = stringResource(R.string.quiet_weekdays).split(",")
        Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SettingsGroupGap)) {
            if (!loaded && !failed && state.runtimeConnected)
                SettingsHint(stringResource(R.string.settings_data_loading))
            if (!scheduleOnly)
                SettingsPreferences(stringResource(R.string.ui_v2_quiet_section)) {
                    SettingsFieldGroup(
                        {
                            BeaconPreferenceToggle(
                                stringResource(R.string.quiet_enable),
                                "",
                                draft.enabled,
                                enabled = editable,
                            ) {
                                draft = draft.copy(enabled = it)
                            }
                        },
                        {
                            BeaconDropdownPreference(
                                stringResource(R.string.quiet_behavior),
                                listOf(
                                    stringResource(R.string.quiet_silent),
                                    stringResource(R.string.quiet_save_only),
                                ),
                                draft.behavior.ordinal,
                                enabled = editable,
                            ) {
                                draft = draft.copy(behavior = QuietHoursBehavior.entries[it])
                            }
                        },
                    )
                    if (onSchedule != null) {
                        SettingsFieldDivider()
                        androidx.compose.material3.Surface(
                            onClick = onSchedule,
                            enabled = editable,
                            color = androidx.compose.ui.graphics.Color.Transparent,
                        ) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = BeaconUiMetrics.FieldMinHeight),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                BeaconText(
                                    stringResource(R.string.quiet_schedule),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                )
                                BeaconText(
                                    "$start – $end",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                androidx.compose.material3.Icon(
                                    BeaconIcons.Arrow,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            if (loaded && draft.enabled && !scheduleValid && !scheduleOnly)
                BeaconText(
                    stringResource(R.string.quiet_invalid),
                    color = MaterialTheme.colorScheme.error,
                )
            val scheduleContent: @Composable ColumnScope.() -> Unit = {
                BeaconTimeRangePreference(
                    start,
                    end,
                    stringResource(R.string.quiet_start),
                    stringResource(R.string.quiet_end),
                    enabled = editable,
                    onStartChange = { start = it },
                    onEndChange = { end = it },
                )
                ActionFlow {
                    labels.forEachIndexed { index, label ->
                        val bit = 1 shl index
                        BeaconFilterChip(
                            draft.weekdays and bit != 0,
                            { draft = draft.copy(weekdays = draft.weekdays xor bit) },
                            { BeaconText(label) },
                            enabled = editable,
                        )
                    }
                }
                if (loaded && !valid)
                    BeaconText(
                        stringResource(R.string.quiet_invalid),
                        color = MaterialTheme.colorScheme.error,
                    )
            }
            if (scheduleOnly || onSchedule == null) {
                SettingsSection(
                    stringResource(R.string.settings_time_range_heading),
                    scheduleContent,
                )
            }
            if (scheduleOnly) {
                SettingsSection(stringResource(R.string.ui_v2_quiet_effect)) {
                    BeaconText(
                        stringResource(
                            if (draft.behavior == QuietHoursBehavior.SILENT) R.string.quiet_silent
                            else R.string.quiet_save_only
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    SettingsHint(stringResource(R.string.ui_v2_quiet_scope_short))
                }
            }
            if (failed) {
                BeaconText(state.status.text, color = MaterialTheme.colorScheme.error)
                BeaconActionButton(
                    stringResource(R.string.action_retry),
                    BeaconIcons.History,
                    Modifier.fillMaxWidth(),
                    !saving && state.runtimeConnected,
                ) {
                    if (loaded) save(actions)
                    else {
                        failed = false
                        attempt++
                    }
                }
            }
        }
    }
}

internal fun parseMinute(value: String): Int? {
    if (!Regex("[0-9]{2}:[0-9]{2}").matches(value)) return null
    val hour = value.substring(0, 2).toInt()
    val minute = value.substring(3).toInt()
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

internal fun formatMinute(value: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", value / 60, value % 60)
