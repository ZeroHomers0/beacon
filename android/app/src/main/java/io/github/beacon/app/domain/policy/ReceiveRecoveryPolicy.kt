package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.*

/** Recovery respects the persisted user switch and any authentication/configuration block. */
object ReceiveRecoveryPolicy {
    /**
     * Returns the reason a start must not be requested, or `null` when the persisted configuration
     * allows one. Shared by boot recovery and bounded unexpected-exit recovery so both apply the
     * same user intent; neither may re-enable receiving on its own.
     */
    fun block(plan: ReceivePlan, state: PollingState): RecoveryStatus? =
        when {
            !state.config.enabled -> RecoveryStatus.DISABLED
            !state.shouldSchedule -> RecoveryStatus.BLOCKED
            plan.mode == ReceiveMode.POWER_SAVING -> RecoveryStatus.POWER_SAVING
            else -> null
        }

    fun restore(plan: ReceivePlan, state: PollingState, start: () -> Unit): RecoveryStatus =
        block(plan, state)
            ?: try {
                start()
                RecoveryStatus.START_REQUESTED
            } catch (_: RuntimeException) {
                RecoveryStatus.START_RESTRICTED
            }
}
