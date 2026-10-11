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
internal fun QuietHoursPanel(state: RuntimeState, actions: RuntimeActions) {
    var saved by remember { mutableStateOf(QuietHours()) }
    var draft by remember { mutableStateOf(saved) }
    var start by remember { mutableStateOf("22:00") }
    var end by remember { mutableStateOf("08:00") }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    fun reset(value: QuietHours) {
        saved = value
        draft = value
        start = formatMinute(value.startMinute)
        end = formatMinute(value.endMinute)
    }
    LaunchedEffect(state.runtimeConnected, attempt) {
        loaded = false
        saving = false
        failed = false
        if (state.runtimeConnected)
            actions.quietHours(null, onFailure = { failed = true }) {
                reset(it)
                loaded = true
            }
    }
    val first = parseMinute(start)
    val last = parseMinute(end)
    val valid = first != null && last != null && first != last && draft.weekdays != 0
    // The two time fields live outside [draft], so the pending value must fold them back in before
    // it can be compared with what is saved; otherwise editing only a time leaves save disabled.
    val changed =
        draft.copy(
            startMinute = first ?: draft.startMinute,
            endMinute = last ?: draft.endMinute,
        ) != saved
    val editable = loaded && !saving && state.runtimeConnected
    val labels = stringResource(R.string.quiet_weekdays).split(",")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiSection(stringResource(R.string.quiet_title)) {
            BeaconPreferenceToggle(
                stringResource(R.string.quiet_enable),
                stringResource(R.string.quiet_description),
                draft.enabled,
                enabled = editable,
            ) {
                draft = draft.copy(enabled = it)
            }
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
            SettingsHint(stringResource(R.string.quiet_behavior_hint))
        }
        UiSection(stringResource(R.string.quiet_schedule)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BeaconTimePreference(
                    start,
                    { start = it },
                    stringResource(R.string.quiet_start),
                    Modifier.weight(1f),
                    enabled = editable,
                )
                BeaconTimePreference(
                    end,
                    { end = it },
                    stringResource(R.string.quiet_end),
                    Modifier.weight(1f),
                    enabled = editable,
                )
            }
            SettingsHint(stringResource(R.string.quiet_time_hint))
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
            SettingsHint(stringResource(R.string.quiet_day_hint))
            if (loaded && !valid)
                BeaconText(
                    stringResource(R.string.quiet_invalid),
                    color = MaterialTheme.colorScheme.error,
                )
            if (failed) {
                BeaconText(state.status.text, color = MaterialTheme.colorScheme.error)
                BeaconActionButton(
                    stringResource(R.string.action_retry),
                    BeaconIcons.History,
                    Modifier.fillMaxWidth(),
                    !saving && state.runtimeConnected,
                ) {
                    failed = false
                    attempt++
                }
            }
            BeaconActionPair(
                dismiss = {
                    BeaconCancelButton(
                        enabled = editable && (changed || !valid),
                        onClick = {
                            reset(saved)
                            failed = false
                        },
                    )
                },
                confirm = {
                    BeaconButton(
                        enabled = editable && valid && changed,
                        onClick = {
                            saving = true
                            failed = false
                            actions.quietHours(
                                draft.copy(startMinute = first!!, endMinute = last!!),
                                onFailure = {
                                    saving = false
                                    failed = true
                                },
                            ) {
                                reset(it)
                                saving = false
                            }
                        },
                    ) {
                        BeaconText(stringResource(R.string.action_save))
                    }
                },
            )
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
