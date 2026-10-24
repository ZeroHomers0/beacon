package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    editorSaving: Boolean = false,
    failureMessage: Int? = null,
    onRetrySave: (() -> Unit)? = null,
) {
    with(form) {
        LaunchedEffect(state.runtimeConnected, attempt) {
            if (!state.runtimeConnected || loaded || loading) return@LaunchedEffect
            loaded = false
            saving = false
            failed = false
            loading = true
            val generation = loadGeneration
            actions.quietHours(
                null,
                onFailure = {
                    if (generation == loadGeneration) {
                        failed = true
                        loading = false
                    }
                },
            ) {
                if (generation == loadGeneration) {
                    reset(it)
                    loaded = true
                    loading = false
                }
            }
        }
        val editable = pageState.hasContent && !saving && !editorSaving && state.runtimeConnected
        val awaitingContent = pageState == AsyncPageState.INITIAL_LOADING
        val labels = stringResource(R.string.quiet_weekdays).split("、")
        Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SettingsGroupGap)) {
            if (!scheduleOnly)
                SettingsPreferences(stringResource(R.string.ui_v2_quiet_section)) {
                    // The section title used to become「设置数据加载中」while loading, which renamed
                    // the page instead of reporting state; the body now owns both.
                    run {
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
                                    if (loaded) draft.behavior.ordinal else -1,
                                    enabled = editable && draft.enabled,
                                ) {
                                    draft = draft.copy(behavior = QuietHoursBehavior.entries[it])
                                }
                            },
                        )
                        if (onSchedule != null) {
                            androidx.compose.material3.Surface(
                                onClick = onSchedule,
                                enabled = editable && draft.enabled,
                                color = androidx.compose.ui.graphics.Color.Transparent,
                            ) {
                                BeaconTimeSummary(
                                    stringResource(R.string.quiet_schedule),
                                    "$start – $end",
                                )
                            }
                        }
                    }
                }
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
                var customDays by remember { mutableStateOf(draft.weekdays != 127 && draft.weekdays != 31) }
                LaunchedEffect(loaded) {
                    if (loaded) customDays = draft.weekdays != 127 && draft.weekdays != 31
                }
                ActionFlow {
                    BeaconFilterChip(draft.weekdays == 127 && !customDays,
                        { draft = draft.copy(weekdays = 127); customDays = false },
                        { BeaconText(stringResource(R.string.settings_every_day)) }, enabled = editable)
                    BeaconFilterChip(draft.weekdays == 31 && !customDays,
                        { draft = draft.copy(weekdays = 31); customDays = false },
                        { BeaconText(stringResource(R.string.settings_work_days)) }, enabled = editable)
                    BeaconFilterChip(customDays, { customDays = true },
                        { BeaconText(stringResource(R.string.settings_custom_days)) }, enabled = editable)
                }
                ActionFlow {
                    labels.forEachIndexed { index, label ->
                        val bit = 1 shl index
                        BeaconFilterChip(
                            draft.weekdays and bit != 0,
                            { customDays = true; draft = draft.copy(weekdays = draft.weekdays xor bit) },
                            { BeaconText(label) },
                            enabled = editable,
                        )
                    }
                }
                SettingsOverviewField(stringResource(R.string.settings_validation_title),
                    stringResource(if (scheduleValid) R.string.settings_validation_ready else R.string.quiet_invalid),
                    error = !scheduleValid)
            }
            if (scheduleOnly || onSchedule == null) {
                SettingsSection(stringResource(R.string.settings_time_range_heading)) {
                    run {
                        scheduleContent()
                        if (scheduleOnly) {
                            SettingsHint(stringResource(if (draft.behavior == QuietHoursBehavior.SILENT)
                                R.string.quiet_silent else R.string.quiet_save_only) + " · " +
                                stringResource(R.string.ui_v2_quiet_scope_short))
                        }
                    }
                }
            }
            if (failed && failureMessage == null) {
                SettingsRetryFeedback(
                    stringResource(R.string.settings_data_failed), failed = true,
                    onRetry =
                        if (!saving && !editorSaving && state.runtimeConnected) {
                            {
                                if (loaded) {
                                    if (onRetrySave != null) onRetrySave() else save(actions)
                                } else {
                                    failed = false
                                    attempt++
                                }
                            }
                        } else null,
                )
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
