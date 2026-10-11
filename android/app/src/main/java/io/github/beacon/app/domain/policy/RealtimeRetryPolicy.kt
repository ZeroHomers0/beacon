package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.*

enum class RealtimeRetryDecision(val wireId: Int) {
    ALLOWED(0),
    NO_CONFIGURATION(1),
    POWER_SAVING(2),
    PAUSED(3),
    AUTH_BLOCKED(4),
    SETTINGS_BLOCKED(5);

    companion object {
        fun fromWireId(id: Int) = entries.firstOrNull { it.wireId == id }
    }
}

object RealtimeRetryPolicy {
    fun decide(plan: ReceivePlan, state: PollingState, configured: Boolean): RealtimeRetryDecision =
        when {
            !configured -> RealtimeRetryDecision.NO_CONFIGURATION
            plan.mode == ReceiveMode.POWER_SAVING -> RealtimeRetryDecision.POWER_SAVING
            !state.config.enabled -> RealtimeRetryDecision.PAUSED
            state.blocked.isNotBlank() && state.lastOutcome == SyncOutcome.AUTH ->
                RealtimeRetryDecision.AUTH_BLOCKED
            state.blocked.isNotBlank() && state.lastOutcome != SyncOutcome.TLS ->
                RealtimeRetryDecision.SETTINGS_BLOCKED
            else -> RealtimeRetryDecision.ALLOWED
        }
}
