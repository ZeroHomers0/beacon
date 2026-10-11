package io.github.beacon.app.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.beacon.app.data.local.PollingStateStore
import io.github.beacon.app.data.local.ReceivePlanStore
import io.github.beacon.app.data.local.RecoveryStatusStore
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.policy.BootRecoveryDecision
import io.github.beacon.app.domain.policy.BootRecoveryPolicy
import io.github.beacon.app.domain.policy.ReceiveRecoveryPolicy

class RealtimeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
                intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        )
            return
        val history = RecoveryStatusStore(context)
        if (
            BootRecoveryPolicy.decide(CredentialAvailability.unlocked(context)) ==
                BootRecoveryDecision.AWAITING_UNLOCK
        ) {
            // Credentials are unavailable, so the persisted plan cannot be read. Nothing was
            // restored, and recording a start would report a recovery that never ran. The wait is
            // parked in device-protected storage and surfaced by the first post-unlock start.
            history.recordAwaitingUnlock()
            return
        }
        try {
            val plan = ReceivePlanStore(context).load()
            val state = PollingStateStore(context).load()
            val result =
                ReceiveRecoveryPolicy.restore(plan, state) {
                    RealtimeServiceLauncher.apply(context, plan, true)
                }
            history.record(result)
        } catch (_: Exception) {
            runCatching { history.record(RecoveryStatus.SETTINGS_ERROR) }
        }
    }
}
