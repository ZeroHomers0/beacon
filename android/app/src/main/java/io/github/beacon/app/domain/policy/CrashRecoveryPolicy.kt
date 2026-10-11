package io.github.beacon.app.domain.policy

/**
 * Bounded unexpected-exit recovery. The policy only decides whether another automatic attempt is
 * allowed and how long to wait; it never starts a service, never clears a user stop and never
 * touches the network. Attempts are capped inside a sliding window instead of retrying forever, and
 * monotonic elapsed time measures delays; wall-clock changes do not reset the budget.
 *
 * A user stop and the receiving switch are deliberately *not* modelled here: both are already
 * persisted, and [ReceiveRecoveryPolicy.block] is the single gate that honours them.
 */
enum class CrashRecoveryDecision {
    /** Start now, or use [CrashRecoveryPolicy.remainingDelayMs] as the scheduling delay. */
    PROCEED,
    DELAYED,
    EXHAUSTED,
    INVALID_CLOCK,
}

data class CrashRecoveryState(
    val attempts: Int = 0,
    val windowStartMs: Long = 0,
    val nextAttemptAtMs: Long = 0,
) {
    fun validate() {
        require(attempts in 0..CrashRecoveryPolicy.MAX_ATTEMPTS)
        require(windowStartMs >= 0)
        require(nextAttemptAtMs >= 0)
    }
}

object CrashRecoveryPolicy {
    /** 5 s / 15 s / 60 s, then a flat 60 s for any further attempt. */
    private val BACKOFF_MS = longArrayOf(5_000L, 15_000L, 60_000L)

    const val WINDOW_MS = 10 * 60 * 1000L
    const val MAX_ATTEMPTS = 5

    /** Recovery target from the requirements: attempt within 60 s of an unexpected exit. */
    const val RECOVERY_DEADLINE_MS = 60_000L

    fun delayMs(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be positive" }
        return BACKOFF_MS[minOf(attempt, BACKOFF_MS.size) - 1]
    }

    /**
     * A window that aged out, or a deadline left behind by a forward clock jump, starts a fresh
     * counter. Neither may be read as one more attempt.
     */
    private fun stale(state: CrashRecoveryState, nowMs: Long) =
        state.windowStartMs == 0L ||
            nowMs - state.windowStartMs >= WINDOW_MS ||
            state.nextAttemptAtMs > nowMs + WINDOW_MS

    fun decide(state: CrashRecoveryState, nowMs: Long): CrashRecoveryDecision =
        when {
            nowMs <= 0 || state.windowStartMs > nowMs || state.nextAttemptAtMs < 0 ->
                CrashRecoveryDecision.INVALID_CLOCK
            stale(state, nowMs) -> CrashRecoveryDecision.PROCEED
            state.attempts >= MAX_ATTEMPTS -> CrashRecoveryDecision.EXHAUSTED
            state.nextAttemptAtMs > nowMs -> CrashRecoveryDecision.DELAYED
            else -> CrashRecoveryDecision.PROCEED
        }

    /** Time still to wait before the next attempt; 0 when it may start now. */
    fun remainingDelayMs(state: CrashRecoveryState, nowMs: Long): Long =
        when (decide(state, nowMs)) {
            CrashRecoveryDecision.DELAYED -> state.nextAttemptAtMs - nowMs
            else -> 0L
        }

    /** Count one attempt and publish the backoff before it runs. */
    fun record(state: CrashRecoveryState, nowMs: Long): CrashRecoveryState {
        state.validate()
        require(nowMs > 0)
        val restart = stale(state, nowMs)
        val attempts = (if (restart) 1 else state.attempts + 1).coerceAtMost(MAX_ATTEMPTS)
        val next =
            CrashRecoveryState(
                attempts,
                if (restart) nowMs else state.windowStartMs,
                nowMs + delayMs(attempts),
            )
        next.validate()
        return next
    }

    fun reset(): CrashRecoveryState = CrashRecoveryState()

    /**
     * Turn the decision into work for the caller.
     *
     * Only [CrashRecoveryAction.START] counts an attempt, so a re-entry that is still waiting keeps
     * its remaining delay and cannot burn the budget twice for one restart. An untrustworthy clock
     * rebases the window rather than stranding the user: a budget that cannot be measured is not a
     * budget. A rebound clock never pauses receiving on the user's behalf.
     */
    fun admit(state: CrashRecoveryState, nowMs: Long): CrashRecoveryAdmission =
        when (decide(state, nowMs)) {
            CrashRecoveryDecision.PROCEED ->
                CrashRecoveryAdmission(
                    CrashRecoveryAction.START,
                    0,
                    if (nowMs > 0) record(state, nowMs) else state,
                )
            CrashRecoveryDecision.DELAYED ->
                CrashRecoveryAdmission(
                    CrashRecoveryAction.WAIT,
                    remainingDelayMs(state, nowMs),
                    state,
                )
            CrashRecoveryDecision.EXHAUSTED ->
                CrashRecoveryAdmission(CrashRecoveryAction.SUSPEND, 0, state)
            CrashRecoveryDecision.INVALID_CLOCK -> {
                val rebased = reset()
                CrashRecoveryAdmission(
                    CrashRecoveryAction.START,
                    0,
                    if (nowMs > 0) record(rebased, nowMs) else rebased,
                )
            }
        }
}

enum class CrashRecoveryAction {
    /** Run the restart and persist the counted attempt. */
    START,

    /** Wait [CrashRecoveryAdmission.delayMs] and ask again; nothing is counted yet. */
    WAIT,

    /** The bounded budget is spent; stay stopped until the user acts. */
    SUSPEND,
}

data class CrashRecoveryAdmission(
    val action: CrashRecoveryAction,
    val delayMs: Long,
    val next: CrashRecoveryState,
) {
    init {
        require(delayMs >= 0)
        next.validate()
    }
}
