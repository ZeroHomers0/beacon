@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.feature.settings

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconActionPair
import io.github.beacon.app.ui.components.BeaconButton
import io.github.beacon.app.ui.components.BeaconOutlinedButton
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.DiagnosticRow
import io.github.beacon.app.ui.components.OperationResultPanel
import io.github.beacon.app.ui.components.SettingsGroup
import io.github.beacon.app.ui.components.SettingsHint
import io.github.beacon.app.ui.components.SettingsRow
import io.github.beacon.app.ui.components.UiSection
import io.github.beacon.app.ui.icons.BeaconIcons

internal fun LazyListScope.diagnosticsSettingsItems(
    runtimeState: RuntimeState,
    actions: RuntimeActions,
    developerExpanded: Boolean,
    onDeveloperExpandedChange: (Boolean) -> Unit,
    onPreviewReport: () -> Unit,
    onOperationDetails: (String) -> Unit,
    busy: Boolean,
    context: Context,
) {
    item {
        UiSection(stringResource(R.string.settings_receive_diagnostics_title)) {
            DiagnosticRow(
                stringResource(R.string.diag_runtime_status),
                stringResource(
                    if (runtimeState.runtimeConnected) R.string.diag_runtime_connected
                    else R.string.diag_runtime_disconnected
                ),
            )
            OperationResultPanel(
                stringResource(R.string.diag_recent_operation),
                runtimeState.status.text,
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
                    BeaconButton(
                        enabled = !busy,
                        onClick = { actions.start(context) },
                    ) {
                        BeaconText(stringResource(R.string.settings_reconnect_data_action))
                    }
                },
            )
            SettingsHint(stringResource(R.string.diag_runtime_hint))
        }
    }
    item {
        io.github.beacon.app.feature.diagnostics.RuntimeEventPanel(
            runtimeState.runtimeConnected,
            actions,
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
                stringResource(R.string.settings_report_privacy_hint),
                BeaconIcons.Messages,
                onPreviewReport,
            )
        }
    }
    item {
        SettingsGroup(stringResource(R.string.settings_dev_test_section_title)) {
            SettingsRow(
                stringResource(
                    if (developerExpanded) R.string.diag_hide_developer
                    else R.string.diag_show_developer
                ),
                stringResource(R.string.diag_developer_hint),
                BeaconIcons.Settings,
            ) {
                onDeveloperExpandedChange(!developerExpanded)
            }
        }
    }
    if (developerExpanded) {
        item {
            UiSection(stringResource(R.string.diag_binding_title)) {
                SettingsHint(stringResource(R.string.diag_unbind_hint))
                BeaconOutlinedButton(
                    enabled = !busy,
                    onClick = { actions.stop() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BeaconText(stringResource(R.string.settings_unbind_diagnostics_action))
                }
            }
        }
        if (BuildConfig.DEBUG) {
            item {
                SettingsGroup(stringResource(R.string.diag_test_actions_title)) {
                    SettingsRow(
                        stringResource(R.string.settings_one_minute_test_title),
                        stringResource(R.string.diag_one_minute_description),
                        BeaconIcons.History,
                    ) {
                        if (!busy) actions.scheduleOneMinuteTest()
                    }
                    SettingsRow(
                        stringResource(R.string.settings_inject_local_test_action),
                        stringResource(R.string.diag_local_message_description),
                        BeaconIcons.Messages,
                    ) {
                        if (!busy) actions.readMessages(injectTest = true)
                    }
                }
            }
        }
    }
}
