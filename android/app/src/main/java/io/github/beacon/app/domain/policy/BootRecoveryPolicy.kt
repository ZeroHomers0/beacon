package io.github.beacon.app.domain.policy

enum class BootRecoveryDecision {
    /**
     * The event was seen but credentials are unavailable, so the persisted plan cannot be read.
     * Recording `START_REQUESTED` here would report a recovery that never happened.
     */
    AWAITING_UNLOCK,
    RESTORE,
}

/**
 * Boot and first-unlock adaptation.
 *
 * Credential availability alone decides whether recovery may run. Reception of `USER_UNLOCKED` is
 * deliberately not assumed: the platform delivers it to runtime-registered receivers only, so the
 * gate is the fact that matters rather than a capability the platform withholds. A locked boot is
 * also not an unexpected exit and must never consume the bounded-exit budget.
 */
object BootRecoveryPolicy {
    fun decide(userUnlocked: Boolean): BootRecoveryDecision =
        if (userUnlocked) BootRecoveryDecision.RESTORE else BootRecoveryDecision.AWAITING_UNLOCK
}
