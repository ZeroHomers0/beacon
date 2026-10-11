package io.github.beacon.app.runtime

import android.content.Context
import android.content.Intent
import io.github.beacon.app.domain.model.ReceiveMode
import io.github.beacon.app.domain.model.ReceivePlan

/** Invoke from a visible client action, or an allowed boot restart. */
object RealtimeServiceLauncher {
    fun apply(context: Context, plan: ReceivePlan, enabled: Boolean) {
        if (enabled && plan.mode != ReceiveMode.POWER_SAVING) {
            context.startForegroundService(
                Intent(context, RuntimeProbeService::class.java)
                    .setAction(RuntimeProbeService.START_RECEIVE)
            )
        }
        // The bound runtime tears down an existing foreground session on config changes.
    }
}
