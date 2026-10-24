package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.ui.components.*

internal fun adminListMatches(query: String, status: Int, revoked: Boolean, vararg values: String): Boolean {
    if (status == 1 && revoked || status == 2 && !revoked) return false
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return terms.all { term -> values.any { it.contains(term, ignoreCase = true) } }
}

@androidx.compose.runtime.Stable
internal class AdminListFilterState {
    var deviceQuery by androidx.compose.runtime.mutableStateOf("")
    var applicationQuery by androidx.compose.runtime.mutableStateOf("")
    var deviceStatus by androidx.compose.runtime.mutableIntStateOf(0)
    var applicationStatus by androidx.compose.runtime.mutableIntStateOf(0)
    var applicationDevice by androidx.compose.runtime.mutableStateOf("")
    var open by androidx.compose.runtime.mutableStateOf(false)
}

@Composable
internal fun AdminStatusFilterAction(filters: AdminListFilterState, application: Boolean,
    devices: List<AdminDevice>, enabled: Boolean) {
    val status = if (application) filters.applicationStatus else filters.deviceStatus
    Box {
        BeaconActionIcon(io.github.beacon.app.ui.icons.BeaconIcons.Filter,
            stringResource(R.string.admin_filter_title), enabled) { filters.open = true }
        BeaconDropdownMenu(filters.open, { filters.open = false }) {
            listOf(R.string.settings_all_status, R.string.admin_active, R.string.admin_revoked)
                .forEachIndexed { index, label ->
                    BeaconDropdownMenuItem({ BeaconSelectableRow(stringResource(label), index == status) }) {
                        if (application) filters.applicationStatus = index else filters.deviceStatus = index
                        filters.open = false
                    }
                }
            if (application) {
                androidx.compose.material3.HorizontalDivider()
                SettingsLabel(stringResource(R.string.settings_selected_device))
                (listOf("" to stringResource(R.string.settings_all_devices)) + devices.map { it.id to it.name.ifBlank { it.id } })
                    .forEach { (id, name) ->
                        BeaconDropdownMenuItem({ BeaconSelectableRow(name, filters.applicationDevice == id) }) {
                            filters.applicationDevice = id; filters.open = false
                        }
                    }
            }
        }
    }
}

@Composable
internal fun AdminListFilters(
    query: String, onQuery: (String) -> Unit,
    status: Int, onStatus: (Int) -> Unit, label: String,
    devices: List<AdminDevice> = emptyList(), selectedDevice: String = "",
    onDevice: ((String) -> Unit)? = null,
    total: Int = 0, active: Int = 0, enabled: Boolean = true,
) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
        BeaconSearchBox(query, onQuery, label, onSearch = { focus.clearFocus() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
            BeaconSummaryPill(stringResource(R.string.admin_stat_total, total), io.github.beacon.app.ui.icons.BeaconIcons.Mail,
                highlighted = status == 0, enabled = enabled, onClick = { onStatus(0) }, Modifier.weight(1f), compact = true)
            BeaconSummaryPill(stringResource(if (status == 2) R.string.admin_stat_revoked else R.string.admin_stat_active,
                if (status == 2) total - active else active), io.github.beacon.app.ui.icons.BeaconIcons.Device,
                highlighted = status != 0, enabled = enabled, onClick = { onStatus(if (status == 2) 2 else 1) },
                Modifier.weight(1f), compact = true)
        }
        if (query.isNotBlank() || status != 0 || selectedDevice.isNotBlank()) ActionFlow {
            if (status != 0) AdminFilterChip(stringResource(if (status == 2) R.string.admin_revoked else R.string.admin_active),
                enabled) { onStatus(0) }
            if (query.isNotBlank()) AdminFilterChip(query, enabled) { onQuery("") }
            if (selectedDevice.isNotBlank()) AdminFilterChip(
                devices.find { it.id == selectedDevice }?.name?.ifBlank { selectedDevice } ?: selectedDevice,
                enabled) { onDevice?.invoke("") }
        }
    }
}

@Composable
private fun AdminFilterChip(label: String, enabled: Boolean, onRemove: () -> Unit) {
    BeaconActiveFilterChip(selected = true, onClick = onRemove, enabled = enabled,
        shape = BeaconUiMetrics.PillShape,
        label = { BeaconText(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        trailingIcon = { androidx.compose.material3.Icon(io.github.beacon.app.ui.icons.BeaconIcons.Close,
            stringResource(R.string.msg_clear_filters_action), Modifier.size(BeaconUiMetrics.IconSmall)) })
}

internal fun priorityShortLabel(value: Int): Int = when {
    value <= 1 -> R.string.settings_priority_save
    value <= 3 -> R.string.settings_priority_silent
    value <= 7 -> R.string.settings_priority_normal
    else -> R.string.settings_priority_important
}
