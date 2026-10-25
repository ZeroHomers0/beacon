package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

/**
 * A destructive confirmation that stays open while its request runs and reports a failure in place.
 * Closing first and raising a snackbar left the reader unsure whether the change had happened, so
 * the dialog keeps both actions busy until the server answers and only then dismisses itself.
 */
@Composable
internal fun AdminActionConfirm(
    title: String,
    description: String,
    state: AdminUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var submitted by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(submitted, state.busy, state.awaiting, state.actionFailure) {
        if (submitted && !state.busy && state.awaiting == null) {
            submitted = false
            failure = state.actionFailure
            if (failure == null) onDismiss()
        }
    }
    BeaconConfirmDialog(
        title = title,
        description = description,
        danger = true,
        enabled = !state.busy && state.awaiting == null,
        dismissEnabled = !state.busy && state.awaiting == null,
        errorText = failure?.let { stringResource(it) },
        statusText = stringResource(if (state.busy || state.awaiting != null) R.string.admin_loading else R.string.settings_form_ready),
        onDismiss = { if (!state.busy && state.awaiting == null) onDismiss() },
        onConfirm = {
            failure = null
            submitted = true
            onConfirm()
        },
    )
}

@Composable
internal fun AdminListCard(
    title: String,
    subtitle: String,
    status: String,
    enabled: Boolean,
    actions: List<Pair<Int, () -> Unit>>,
    modifier: Modifier = Modifier,
    revoked: Boolean = false,
    priority: Int? = null,
    details: String = subtitle,
    onDetails: (() -> Unit)? = null,
) {
    var showingDetails by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    if (showingDetails) BeaconAlertDialog(
        onDismissRequest = { showingDetails = false },
        title = { BeaconText(title) },
        text = {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12)) {
                    BeaconText(details)
                    BeaconText(status)
                    if (priority != null) BeaconText(stringResource(priorityShortLabel(priority)))
                }
            }
        },
        confirmButton = { BeaconButton({ showingDetails = false }) { BeaconText(stringResource(R.string.action_close)) } },
    )
    BeaconListCard(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap4)) {
                BeaconFittedText(title, Modifier.fillMaxWidth(),
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium.copy(
                        fontSize = BeaconListText.TitleSize, lineHeight = BeaconListText.TitleLineHeight))
                BeaconFittedText(subtitle, Modifier.fillMaxWidth(),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FirstLineSlot(BeaconListText.TitleLineHeight) {
              Box {
                BeaconIconButton(onClick = { menuOpen = true }, enabled = enabled,
                    modifier = Modifier.size(BeaconUiMetrics.MessageCardActionSize)) {
                    androidx.compose.material3.Icon(BeaconIcons.More,
                        stringResource(R.string.admin_more_actions),
                        Modifier.size(BeaconUiMetrics.MessageCardActionIconSize))
                }
                BeaconDropdownMenu(menuOpen && enabled, { menuOpen = false }) {
                    BeaconDropdownMenuItem({ BeaconText(stringResource(R.string.settings_view_details)) }) {
                        menuOpen = false; if(onDetails!=null)onDetails()else showingDetails = true
                    }
                    actions.forEach { (label, action) ->
                        BeaconDropdownMenuItem({ BeaconText(stringResource(label), color =
                            if (label in setOf(R.string.admin_delete_device, R.string.admin_revoke_device,
                                R.string.admin_delete_application, R.string.admin_revoke_application, R.string.admin_topic_clear, R.string.admin_topic_delete))
                                androidx.compose.material3.MaterialTheme.colorScheme.error
                            else androidx.compose.material3.MaterialTheme.colorScheme.onSurface) }) {
                            menuOpen = false
                            action()
                        }
                    }
                }
              }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
            Spacer(Modifier.weight(1f))
            MessageFilterTag(status, compact = true,
                containerColor = androidx.compose.ui.graphics.Color(if (revoked) 0xFFFF8F96 else 0xFF82E6A4))
            if (priority != null) MessageFilterTag(stringResource(priorityShortLabel(priority)),
                modifier = Modifier.widthIn(max = 180.dp), compact = true,
                containerColor = androidx.compose.ui.graphics.Color(when {
                    priority <= 1 -> 0xFFE8F3FF
                    priority <= 3 -> 0xFFBEDDFF
                    priority <= 7 -> 0xFF8FC5FF
                    else -> 0xFF5AA7F2
                }))
        }
    }
}

@Composable
internal fun DevicePicker(
    devices: List<AdminDevice>,
    selected: String,
    change: (String) -> Unit,
    enabled: Boolean,
) {
        BeaconDropdownPreference(
            stringResource(R.string.admin_device),
            devices.map { "${it.name} · ${it.id.take(8)}" }
                .ifEmpty { listOf(stringResource(R.string.admin_no_active_devices)) },
            devices.indexOfFirst { it.id == selected }.coerceAtLeast(0),
            enabled = enabled && devices.isNotEmpty(),
            stacked = true,
        ) {
            devices.getOrNull(it)?.let { device -> change(device.id) }
        }
}

/**
 * The four tiers the client can actually tell apart: 0 never notifies, 1-3 use the silent channel,
 * 4-7 the normal one and 8-10 the important one. The server, the wire protocol and the Bark entry
 * still accept the whole 0-10 range, so a narrow picker never narrows the contract.
 */
internal val PRIORITY_TIERS = listOf(0, 2, 5, 8)

internal fun priorityLabel(value: Int): Int =
    when (value) {
        0 -> R.string.admin_priority_save
        in 1..3 -> R.string.admin_priority_silent
        in 4..7 -> R.string.admin_priority_normal
        else -> R.string.admin_priority_important
    }
