package io.github.beacon.app.domain.policy

import org.junit.Assert.*
import org.junit.Test

class CrashRecoveryPolicyTest {
    private val now = 1_700_000_000_000L

    @Test
    fun repeatedAlarmAdmissionsCannotBypassBudget() {
        var time = now
        var state = CrashRecoveryPolicy.reset()
        repeat(CrashRecoveryPolicy.MAX_ATTEMPTS) {
            val start = CrashRecoveryPolicy.admit(state, time)
            assertEquals(CrashRecoveryAction.START, start.action)
            state = start.next
            if (state.attempts < CrashRecoveryPolicy.MAX_ATTEMPTS) {
                val wait = CrashRecoveryPolicy.admit(state, time)
                assertEquals(CrashRecoveryAction.WAIT, wait.action)
                assertEquals(state, wait.next)
            }
            time = state.nextAttemptAtMs
        }
        assertEquals(CrashRecoveryAction.SUSPEND, CrashRecoveryPolicy.admit(state, time).action)
    }

    @Test
    fun backoffGrowsThenFlattensAtTheLongestStep() {
        assertEquals(5_000L, CrashRecoveryPolicy.delayMs(1))
        assertEquals(15_000L, CrashRecoveryPolicy.delayMs(2))
        assertEquals(60_000L, CrashRecoveryPolicy.delayMs(3))
        assertEquals(60_000L, CrashRecoveryPolicy.delayMs(4))
        assertEquals(60_000L, CrashRecoveryPolicy.delayMs(9))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroAttemptHasNoBackoff() {
        CrashRecoveryPolicy.delayMs(0)
    }

    @Test
    fun firstAttemptStartsImmediatelyAndWithinTheDeadline() {
        val fresh = CrashRecoveryPolicy.reset()
        assertEquals(CrashRecoveryDecision.PROCEED, CrashRecoveryPolicy.decide(fresh, now))

        val recorded = CrashRecoveryPolicy.record(fresh, now)
        assertEquals(1, recorded.attempts)
        assertEquals(now, recorded.windowStartMs)
        assertTrue(CrashRecoveryPolicy.delayMs(1) <= CrashRecoveryPolicy.RECOVERY_DEADLINE_MS)
        assertEquals(CrashRecoveryDecision.DELAYED, CrashRecoveryPolicy.decide(recorded, now))
    }

    @Test
    fun delayIsReportedUntilTheDeadlinePasses() {
        val recorded = CrashRecoveryPolicy.record(CrashRecoveryPolicy.reset(), now)
        assertEquals(5_000L, CrashRecoveryPolicy.remainingDelayMs(recorded, now))
        assertEquals(3_000L, CrashRecoveryPolicy.remainingDelayMs(recorded, now + 2_000L))
        assertEquals(0L, CrashRecoveryPolicy.remainingDelayMs(recorded, now + 5_000L))
        assertEquals(
            CrashRecoveryDecision.PROCEED,
            CrashRecoveryPolicy.decide(recorded, now + 5_000L),
        )
    }

    @Test
    fun attemptsAreCappedInsideTheWindow() {
        var state = CrashRecoveryPolicy.reset()
        for (step in 1..CrashRecoveryPolicy.MAX_ATTEMPTS) {
            state = CrashRecoveryPolicy.record(state, now + step * 60_000L)
            assertEquals(step, state.attempts)
        }
        assertEquals(CrashRecoveryPolicy.MAX_ATTEMPTS, state.attempts)
        assertEquals(
            CrashRecoveryDecision.EXHAUSTED,
            CrashRecoveryPolicy.decide(state, state.nextAttemptAtMs),
        )
    }

    @Test
    fun anExpiredWindowRestartsTheBudgetInsteadOfStayingExhausted() {
        var state = CrashRecoveryPolicy.reset()
        for (step in 1..CrashRecoveryPolicy.MAX_ATTEMPTS) {
            state = CrashRecoveryPolicy.record(state, now + step * 60_000L)
        }
        val later = state.windowStartMs + CrashRecoveryPolicy.WINDOW_MS
        assertEquals(CrashRecoveryDecision.PROCEED, CrashRecoveryPolicy.decide(state, later))

        val restarted = CrashRecoveryPolicy.record(state, later)
        assertEquals(1, restarted.attempts)
        assertEquals(later, restarted.windowStartMs)
    }

    @Test
    fun aForwardClockJumpDoesNotBurnTheBudget() {
        val state = CrashRecoveryPolicy.record(CrashRecoveryPolicy.reset(), now)
        val jumped = now + CrashRecoveryPolicy.WINDOW_MS * 3
        assertEquals(CrashRecoveryDecision.PROCEED, CrashRecoveryPolicy.decide(state, jumped))
        assertEquals(1, CrashRecoveryPolicy.record(state, jumped).attempts)
    }

    @Test
    fun invalidClocksPermitNothing() {
        val recorded = CrashRecoveryPolicy.record(CrashRecoveryPolicy.reset(), now)
        for (moment in listOf(0L, -1L, recorded.windowStartMs - 1)) {
            assertEquals(
                CrashRecoveryDecision.INVALID_CLOCK,
                CrashRecoveryPolicy.decide(recorded, moment),
            )
            assertEquals(0L, CrashRecoveryPolicy.remainingDelayMs(recorded, moment))
        }
        val negativeDeadline = recorded.copy(nextAttemptAtMs = -1L)
        assertEquals(
            CrashRecoveryDecision.INVALID_CLOCK,
            CrashRecoveryPolicy.decide(negativeDeadline, now),
        )
    }

    @Test
    fun aFreshStateIsStaleRatherThanExhausted() {
        val untouched = CrashRecoveryState()
        assertEquals(
            CrashRecoveryDecision.PROCEED,
            CrashRecoveryPolicy.decide(untouched, now),
        )
        assertEquals(CrashRecoveryDecision.PROCEED, CrashRecoveryPolicy.decide(untouched, 1L))
    }

    @Test
    fun stateOutsideTheBudgetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CrashRecoveryState(attempts = CrashRecoveryPolicy.MAX_ATTEMPTS + 1).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            CrashRecoveryState(windowStartMs = -1).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            CrashRecoveryState(nextAttemptAtMs = -1).validate()
        }
    }

    @Test
    fun recordRequiresAUsableClock() {
        assertThrows(IllegalArgumentException::class.java) {
            CrashRecoveryPolicy.record(CrashRecoveryPolicy.reset(), 0)
        }
    }

    @Test
    fun anUnexpectedExitStartsImmediatelyAndCountsOneAttempt() {
        val admission = CrashRecoveryPolicy.admit(CrashRecoveryPolicy.reset(), now)
        assertEquals(CrashRecoveryAction.START, admission.action)
        assertEquals(0L, admission.delayMs)
        assertEquals(1, admission.next.attempts)
        assertEquals(now, admission.next.windowStartMs)
    }

    @Test
    fun aWaitingRetryKeepsItsRemainingDelayInsteadOfCountingTwice() {
        val counted =
            CrashRecoveryPolicy.record(CrashRecoveryPolicy.reset(), now).let {
                assertEquals(1, it.attempts)
                it
            }
        val first = CrashRecoveryPolicy.admit(counted, now)
        assertEquals(CrashRecoveryAction.WAIT, first.action)
        assertEquals(5_000L, first.delayMs)
        assertEquals(counted, first.next)

        // Re-entering mid-wait (a sticky redelivery, the alarm firing twice) must not spend budget.
        val again = CrashRecoveryPolicy.admit(first.next, now + 2_000L)
        assertEquals(CrashRecoveryAction.WAIT, again.action)
        assertEquals(3_000L, again.delayMs)
        assertEquals(counted, again.next)
    }

    @Test
    fun theAttemptThatTheAlarmWasScheduledForRunsWhenTheWaitElapses() {
        val counted = CrashRecoveryPolicy.record(CrashRecoveryPolicy.reset(), now)
        val due = CrashRecoveryPolicy.admit(counted, counted.nextAttemptAtMs)
        assertEquals(CrashRecoveryAction.START, due.action)
        assertEquals(2, due.next.attempts)
    }

    @Test
    fun aSpentBudgetSuspendsInsteadOfRetryingForever() {
        var state = CrashRecoveryPolicy.reset()
        for (step in 1..CrashRecoveryPolicy.MAX_ATTEMPTS) {
            state = CrashRecoveryPolicy.record(state, now + step * 60_000L)
        }
        val admission = CrashRecoveryPolicy.admit(state, state.nextAttemptAtMs)
        assertEquals(CrashRecoveryAction.SUSPEND, admission.action)
        assertEquals(0L, admission.delayMs)
        // Suspension adds nothing: the recorded budget is already the whole story.
        assertEquals(state, admission.next)
    }

    @Test
    fun anUntrustworthyClockNeverSuspendsTheUser() {
        val counted = CrashRecoveryPolicy.record(CrashRecoveryPolicy.reset(), now)
        val backwards = CrashRecoveryPolicy.admit(counted, counted.windowStartMs - 1)
        assertEquals(CrashRecoveryAction.START, backwards.action)
        assertEquals(1, backwards.next.attempts)

        // A clock that cannot be read at all still permits the start, but cannot be counted.
        val unusable = CrashRecoveryPolicy.admit(counted, 0)
        assertEquals(CrashRecoveryAction.START, unusable.action)
        assertEquals(0L, unusable.delayMs)
        assertEquals(CrashRecoveryPolicy.reset(), unusable.next)
    }
}
