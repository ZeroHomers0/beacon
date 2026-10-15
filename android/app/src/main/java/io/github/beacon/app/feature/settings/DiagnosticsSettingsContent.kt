@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconActionPair
import io.github.beacon.app.ui.components.BeaconButton
import io.github.beacon.app.ui.components.BeaconOutlinedButton
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.SettingsStatusSummary
import io.github.beacon.app.ui.components.SettingsGroup
import io.github.beacon.app.ui.components.SettingsHint
import io.github.beacon.app.ui.components.SettingsRow
import io.github.beacon.app.ui.components.SettingsSection
import io.github.beacon.app.ui.icons.BeaconIcons

internal fun LazyListScope.diagnosticsSettingsItems(
    runtimeState: RuntimeState,
    actions: RuntimeActions,
    onPreviewReport: () -> Unit,
    onOperationDetails: (String) -> Unit,
    busy: Boolean,
    context: Context,
) {
    item {
        SettingsSection(stringResource(R.string.settings_receive_diagnostics_title)) {
            SettingsStatusSummary(
                stringResource(R.string.diag_runtime_status),
                stringResource(
                    if (runtimeState.runtimeConnected) R.string.diag_runtime_connected
                    else R.string.diag_runtime_disconnected
                ),
                icon = if (runtimeState.runtimeConnected) BeaconIcons.Check else BeaconIcons.Warning,
                tint = if (runtimeState.runtimeConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            BeaconActionPair(
                dismiss = {
                    BeaconOutlinedButton(
                        enabled = !busy && runtimeState.runtimeConnected,
                        onClick = { actions.refreshPolling(onOperationDetails) },
                    ) {
                        BeaconText(stringResource(R.string.action_refresh_status))
                    }
                },
                confirm = {
                    BeaconOutlinedButton(
                        enabled = !busy && !runtimeState.runtimeConnected,
                        onClick = { actions.start(context) },
                    ) {
                        BeaconText(stringResource(R.string.settings_reconnect_data_action))
                    }
                },
            )
        }
    }
    item {
        io.github.beacon.app.feature.diagnostics.RuntimeEventPanel(
            runtimeState.runtimeConnected,
            actions,
            compact = true,
        )
    }
    item {
        io.github.beacon.app.feature.diagnostics.RealtimeDiagnosticPanel(
            runtimeState,
            actions,
        )
    }
    item {
        SettingsGroup(stringResource(R.string.settings_redact_section_title)) {
            SettingsRow(
                stringResource(R.string.settings_preview_report_action),
                "",
                BeaconIcons.Messages,
                onPreviewReport,
            )
        }
    }
}
