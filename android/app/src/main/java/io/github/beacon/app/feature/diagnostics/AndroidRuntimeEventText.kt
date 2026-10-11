package io.github.beacon.app.feature.diagnostics

import android.content.Context
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.RealtimeFailure
import io.github.beacon.app.domain.model.RuntimeEventKind
import io.github.beacon.app.domain.model.SyncOutcome

/**
 * Resolves [RuntimeEventText] from resources. Kept outside the interface so the export format stays
 * free of Android types and remains testable on a plain JVM.
 */
class AndroidRuntimeEventText(private val context: Context) : RuntimeEventText {
    override val title: String
        get() = context.getString(R.string.event_export_title)

    override val disclaimer: String
        get() = context.getString(R.string.event_export_disclaimer)

    override fun kind(kind: RuntimeEventKind) =
        context.getString(
            when (kind) {
                RuntimeEventKind.SERVICE_STARTED -> R.string.event_kind_service_started
                RuntimeEventKind.MANUAL_SYNC -> R.string.event_kind_manual_sync
                RuntimeEventKind.AUTO_SYNC -> R.string.event_kind_auto_sync
                RuntimeEventKind.REALTIME_CONNECTING -> R.string.event_kind_realtime_connecting
                RuntimeEventKind.REALTIME_CONNECTED -> R.string.event_kind_realtime_connected
                RuntimeEventKind.REALTIME_RETRYING -> R.string.event_kind_realtime_retrying
                RuntimeEventKind.REALTIME_STOPPED -> R.string.event_kind_realtime_stopped
                RuntimeEventKind.REALTIME_CONFIG_FAILED ->
                    R.string.event_kind_realtime_config_failed
                RuntimeEventKind.NETWORK_CHANGED -> R.string.event_kind_network_changed
                RuntimeEventKind.MODE_SAVED -> R.string.event_kind_mode_saved
                RuntimeEventKind.RECEIVE_ENABLED -> R.string.event_kind_receive_enabled
                RuntimeEventKind.RECEIVE_PAUSED -> R.string.event_kind_receive_paused
                RuntimeEventKind.CONNECTION_CLEARED -> R.string.event_kind_connection_cleared
                RuntimeEventKind.RECONNECT_REQUESTED -> R.string.event_kind_reconnect_requested
                RuntimeEventKind.REALTIME_REJECTED -> R.string.event_kind_realtime_rejected
                RuntimeEventKind.WAIT_NETWORK -> R.string.event_kind_wait_network
                RuntimeEventKind.WAIT_RULES -> R.string.event_kind_wait_rules
                RuntimeEventKind.CONNECTION_SAVED -> R.string.event_kind_connection_saved
                RuntimeEventKind.CREDENTIALS_ROTATED -> R.string.event_kind_credentials_rotated
                RuntimeEventKind.DEVICE_REVOKED -> R.string.event_kind_device_revoked
                RuntimeEventKind.RECOVERY_AWAITING_UNLOCK ->
                    R.string.event_kind_recovery_awaiting_unlock
                RuntimeEventKind.UNEXPECTED_EXIT_RECOVERY ->
                    R.string.event_kind_unexpected_exit_recovery
                RuntimeEventKind.RECOVERY_SUSPENDED -> R.string.event_kind_recovery_suspended
            }
        )

    override fun outcome(outcome: SyncOutcome) =
        context.getString(
            when (outcome) {
                SyncOutcome.OK -> R.string.event_outcome_ok
                SyncOutcome.STOPPED -> R.string.event_outcome_stopped
                SyncOutcome.TRANSIENT -> R.string.event_outcome_transient
                SyncOutcome.AUTH -> R.string.event_outcome_auth
                SyncOutcome.TLS -> R.string.event_outcome_tls
                SyncOutcome.PROTOCOL -> R.string.event_outcome_protocol
                SyncOutcome.STORAGE -> R.string.event_outcome_storage
                SyncOutcome.CONFIG -> R.string.event_outcome_config
                SyncOutcome.BUSY -> R.string.event_outcome_busy
            }
        )

    override fun failure(failure: RealtimeFailure): String? {
        val resource =
            when (failure) {
                RealtimeFailure.NONE -> return null
                RealtimeFailure.AUTH -> R.string.event_failure_auth
                RealtimeFailure.CERTIFICATE_EXPIRED -> R.string.event_failure_certificate_expired
                RealtimeFailure.CERTIFICATE_NOT_YET_VALID ->
                    R.string.event_failure_certificate_not_yet_valid
                RealtimeFailure.CERTIFICATE_UNTRUSTED ->
                    R.string.event_failure_certificate_untrusted
                RealtimeFailure.HOSTNAME -> R.string.event_failure_hostname
                RealtimeFailure.TLS_INTERRUPTED -> R.string.event_failure_tls_interrupted
                RealtimeFailure.DNS -> R.string.event_failure_dns
                RealtimeFailure.TIMEOUT -> R.string.event_failure_timeout
                RealtimeFailure.CONNECTION -> R.string.event_failure_connection
                RealtimeFailure.CONFIG -> R.string.event_failure_config
                RealtimeFailure.NETWORK -> R.string.event_failure_network
                RealtimeFailure.INTERNAL -> R.string.event_failure_internal
            }
        return context.getString(resource)
    }
}
