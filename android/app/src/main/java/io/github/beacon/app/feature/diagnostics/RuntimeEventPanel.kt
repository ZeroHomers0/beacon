package io.github.beacon.app.feature.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.RuntimeEvent
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*

@Composable
internal fun RuntimeEventPanel(connected: Boolean, actions: RuntimeActions, compact: Boolean = false) {
    val context = LocalContext.current
    val text = remember(context) { AndroidRuntimeEventText(context) }
    var events by remember { mutableStateOf<List<RuntimeEvent>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var generation by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val failureText = stringResource(R.string.events_failed)
    val clearedText = stringResource(R.string.events_cleared)
    val copiedText = stringResource(R.string.events_copied)
    val clipLabel = stringResource(R.string.events_clip_label)
    fun copyRecords() {
        copyToClipboard(context, clipLabel, text.export(events), sensitive = true)
        feedback = copiedText
    }
    fun refresh() {
        val capture = ++generation
        busy = true
        feedback = ""
        actions.eventLog(
            onFailure = {
                if (capture == generation) {
                    busy = false
                    feedback = failureText
                }
            }
        ) {
            if (capture == generation) {
                events = it
                loaded = true
                busy = false
            }
        }
    }
    LaunchedEffect(connected) {
        generation++
        busy = false
        if (connected) refresh()
    }
    SettingsSection(stringResource(R.string.events_title)) {
        if (compact && feedback.isNotBlank()) SettingsHint(feedback)
        if (compact) {
            BeaconActionButton(
                stringResource(R.string.events_view_all),
                io.github.beacon.app.ui.icons.BeaconIcons.History,
                Modifier.fillMaxWidth(),
                connected && !busy,
            ) { expanded = true }
        } else {
            if (events.isEmpty())
                BeaconText(
                    stringResource(if (loaded) R.string.events_empty else R.string.events_not_loaded)
                )
            else events.takeLast(3).asReversed().forEach { EventRow(it, text) }
            if (feedback.isNotBlank()) SettingsHint(feedback)
            BeaconActionPair(
                dismiss = {
                    BeaconOutlinedButton(onClick = { refresh() }, enabled = connected && !busy) {
                        BeaconText(
                            stringResource(
                                if (busy) R.string.events_loading else R.string.action_refresh_status
                            )
                        )
                    }
                },
                confirm = {
                    BeaconButton(
                        onClick = { expanded = true },
                        enabled = !busy && events.isNotEmpty(),
                    ) {
                        BeaconText(stringResource(R.string.events_view_all))
                    }
                },
            )
            BeaconActionPair(
                dismiss = {
                    BeaconOutlinedButton(
                        onClick = {
                            copyRecords()
                        },
                        enabled = !busy && events.isNotEmpty(),
                    ) {
                        BeaconText(stringResource(R.string.events_copy))
                    }
                },
                confirm = {
                    BeaconOutlinedButton(
                        onClick = { confirmClear = true },
                        enabled = connected && !busy,
                    ) {
                        BeaconText(stringResource(R.string.events_clear))
                    }
                },
            )
        }
    }
    if (expanded)
        BeaconAlertDialog(
            onDismissRequest = { expanded = false },
            title = { BeaconText(stringResource(R.string.events_title)) },
            dismissButton = if (compact) null else {
                {
                    BeaconOutlinedButton(onClick = { copyRecords() }) {
                        BeaconText(stringResource(R.string.events_copy))
                    }
                }
            },
            text = {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (events.isEmpty()) item { BeaconText(stringResource(if (loaded) R.string.events_empty else R.string.events_not_loaded)) }
                    if (feedback.isNotBlank()) item { SettingsHint(feedback) }
                    items(events.asReversed()) { EventRow(it, text) }
                }
            },
            confirmButton = {
                if (compact) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BeaconActionPair(
                        { BeaconOutlinedButton(onClick = { copyRecords() }, enabled = events.isNotEmpty()) { BeaconText(stringResource(R.string.events_copy)) } },
                        { BeaconButton(onClick = { expanded = false }) { BeaconText(stringResource(R.string.action_close)) } },
                    )
                    BeaconOutlinedButton(
                        onClick = { expanded = false; confirmClear = true },
                        modifier = Modifier.fillMaxWidth(), enabled = connected && !busy,
                    ) { BeaconText(stringResource(R.string.events_clear)) }
                } else BeaconButton(onClick = { expanded = false }) {
                    BeaconText(stringResource(R.string.action_close))
                }
            },
        )
    if (confirmClear)
        BeaconConfirmDialog(
            title = stringResource(R.string.events_clear),
            description = stringResource(R.string.events_clear_note),
            confirmText = stringResource(R.string.events_confirm_clear),
            danger = false,
            enabled = connected && !busy,
            dismissEnabled = !busy,
            onDismiss = { if (!busy) confirmClear = false },
            onConfirm = {
                val capture = ++generation
                busy = true
                actions.clearEventLog(
                    onFailure = {
                        if (capture == generation) {
                            busy = false
                            feedback = failureText
                        }
                    }
                ) {
                    if (capture == generation) {
                        busy = false
                        events = emptyList()
                        loaded = true
                        confirmClear = false
                        feedback = clearedText
                    }
                }
            },
        )
}

@Composable
private fun EventRow(event: RuntimeEvent, text: RuntimeEventText) {
    androidx.compose.material3.Surface(
        Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BeaconText(text.label(event), style = MaterialTheme.typography.bodyMedium)
            BeaconText(
                formatMessageTime(event.atMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
