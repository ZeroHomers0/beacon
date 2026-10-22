package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.ui.components.*

internal fun adminListMatches(query: String, status: Int, revoked: Boolean, vararg values: String): Boolean {
    if (status == 1 && revoked || status == 2 && !revoked) return false
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return terms.all { term -> values.any { it.contains(term, ignoreCase = true) } }
}

@Composable
internal fun AdminListFilters(
    query: String, onQuery: (String) -> Unit,
    status: Int, onStatus: (Int) -> Unit, label: String,
    devices: List<AdminDevice> = emptyList(), selectedDevice: String = "",
    onDevice: ((String) -> Unit)? = null,
) {
    SettingsSection(stringResource(R.string.ui_filter_messages_title)) {
        BeaconTextField(query, onQuery, Modifier.fillMaxWidth(),
            label = { BeaconText(label) }, labelText = label, singleLine = true)
        BeaconDropdownPreference(stringResource(R.string.settings_current_state), listOf(
            stringResource(R.string.settings_all_status), stringResource(R.string.admin_active),
            stringResource(R.string.admin_revoked)), status, onSelected = onStatus)
        if (onDevice != null) {
            val ids = listOf("") + devices.map { it.id }
            BeaconDropdownPreference(stringResource(R.string.settings_selected_device),
                listOf(stringResource(R.string.settings_all_devices)) + devices.map { it.name.ifBlank { it.id.take(12) } },
                ids.indexOf(selectedDevice).coerceAtLeast(0)) { onDevice(ids[it]) }
        }
        if (query.isNotBlank() || status != 0 || selectedDevice.isNotBlank()) SettingsActions {
            SettingsTextAction(stringResource(R.string.msg_clear_filters_action)) {
                onQuery(""); onStatus(0); onDevice?.invoke("")
            }
        }
    }
}

internal fun priorityShortLabel(value: Int): Int = when {
    value <= 1 -> R.string.settings_priority_save
    value <= 3 -> R.string.settings_priority_silent
    value <= 7 -> R.string.settings_priority_normal
    else -> R.string.settings_priority_important
}
