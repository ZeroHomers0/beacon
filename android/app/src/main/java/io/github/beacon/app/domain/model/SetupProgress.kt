package io.github.beacon.app.domain.model

/** Readiness checks are separate from actual message/notification delivery. */
data class SetupProgress(val connection: Boolean, val notifications: Boolean, val sync: Boolean) {
    companion object {
        fun evaluate(
            configured: Boolean,
            reachable: Boolean?,
            notifications: Boolean,
            outcome: SyncOutcome?,
        ) =
            SetupProgress(
                configured && reachable == true,
                notifications,
                configured && reachable == true && outcome == SyncOutcome.OK,
            )
    }
}
