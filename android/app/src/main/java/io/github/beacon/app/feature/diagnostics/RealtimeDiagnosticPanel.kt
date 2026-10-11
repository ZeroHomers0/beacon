package io.github.beacon.app.feature.diagnostics

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
internal fun RealtimeDiagnosticPanel(
    state: RuntimeState,
    actions: RuntimeActions,
) {
    val diagnostics = state.realtimeDiagnostics
    UiSection(stringResource(R.string.realtime_diag_title)) {
        OperationResultPanel(
            stringResource(R.string.realtime_diag_status_label),
            state.realtimeStatus,
        )
        DiagnosticRow(
            stringResource(R.string.realtime_diag_failure_label),
            stringResource(
                when (diagnostics.failure) {
                    RealtimeFailure.NONE -> R.string.realtime_diag_failure_none
                    RealtimeFailure.AUTH -> R.string.realtime_diag_failure_auth
                    RealtimeFailure.CERTIFICATE_EXPIRED ->
                        R.string.realtime_diag_failure_certificate_expired
                    RealtimeFailure.CERTIFICATE_NOT_YET_VALID ->
                        R.string.realtime_diag_failure_certificate_not_yet_valid
                    RealtimeFailure.CERTIFICATE_UNTRUSTED ->
                        R.string.realtime_diag_failure_certificate_untrusted
                    RealtimeFailure.HOSTNAME -> R.string.realtime_diag_failure_hostname
                    RealtimeFailure.TLS_INTERRUPTED ->
                        R.string.realtime_diag_failure_tls_interrupted
                    RealtimeFailure.DNS -> R.string.realtime_diag_failure_dns
                    RealtimeFailure.TIMEOUT -> R.string.realtime_diag_failure_timeout
                    RealtimeFailure.CONNECTION -> R.string.realtime_diag_failure_connection
                    RealtimeFailure.CONFIG -> R.string.realtime_diag_failure_config
                    RealtimeFailure.NETWORK -> R.string.realtime_diag_failure_network
                    RealtimeFailure.INTERNAL -> R.string.realtime_diag_failure_internal
                }
            ),
        )
        DiagnosticRow(
            stringResource(R.string.realtime_diag_attempts_label),
            stringResource(
                R.string.realtime_diag_attempts_value,
                diagnostics.attempts,
                diagnostics.retries,
            ),
        )
        DiagnosticRow(
            stringResource(R.string.realtime_diag_connected_at_label),
            time(diagnostics.connectedAtMs),
        )
        BeaconActionButton(
            stringResource(R.string.realtime_retry),
            BeaconIcons.Realtime,
            Modifier.fillMaxWidth(),
            state.runtimeConnected && !state.messageBusy,
            actions::reconnectRealtime,
        )
        SettingsHint(stringResource(R.string.realtime_retry_note))
        DiagnosticRow(
            stringResource(R.string.realtime_diag_recovery_label),
            stringResource(
                when (state.recovery.status) {
                    RecoveryStatus.NONE -> R.string.realtime_diag_recovery_none
                    RecoveryStatus.DISABLED -> R.string.realtime_diag_recovery_disabled
                    RecoveryStatus.BLOCKED -> R.string.realtime_diag_recovery_blocked
                    RecoveryStatus.POWER_SAVING -> R.string.realtime_diag_recovery_power_saving
                    RecoveryStatus.START_REQUESTED ->
                        R.string.realtime_diag_recovery_start_requested
                    RecoveryStatus.START_RESTRICTED ->
                        R.string.realtime_diag_recovery_start_restricted
                    RecoveryStatus.SETTINGS_ERROR -> R.string.realtime_diag_recovery_settings_error
                    RecoveryStatus.AWAITING_UNLOCK ->
                        R.string.realtime_diag_recovery_awaiting_unlock
                    RecoveryStatus.CRASH_RETRY_WAITING ->
                        R.string.realtime_diag_recovery_crash_retry_waiting
                    RecoveryStatus.CRASH_SUSPENDED ->
                        R.string.realtime_diag_recovery_crash_suspended
                }
            ),
        )
        DiagnosticRow(
            stringResource(R.string.realtime_diag_recovery_at_label),
            time(state.recovery.atMs),
        )
        if (state.recovery.attempts > 0)
            DiagnosticRow(
                stringResource(R.string.realtime_diag_recovery_attempts_label),
                state.recovery.attempts.toString(),
            )
        SettingsHint(stringResource(R.string.realtime_diag_recovery_note))
    }
}

@Composable
private fun time(value: Long): String =
    if (value <= 0) stringResource(R.string.realtime_diag_recovery_none)
    else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(value))
