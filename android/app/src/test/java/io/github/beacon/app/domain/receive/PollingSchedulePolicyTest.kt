package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.PollingConfig
import io.github.beacon.app.domain.model.PollingState
import org.junit.Assert.assertEquals
import org.junit.Test

class PollingSchedulePolicyTest {
    private val enabled = PollingState(PollingConfig(enabled = true), revision = 10)

    @Test
    fun firstConnectionRestoresPersistentScheduleAndPauseCancelsIt() {
        assertEquals(ScheduleAction.ENABLE, PollingSchedulePolicy.decide(null, enabled))
        assertEquals(
            ScheduleAction.CANCEL,
            PollingSchedulePolicy.decide(
                enabled,
                enabled.copy(config = enabled.config.copy(enabled = false), revision = 11),
            ),
        )
        assertEquals(ScheduleAction.CANCEL, PollingSchedulePolicy.decide(null, PollingState()))
    }

    @Test
    fun repairedCredentialsRestoreScheduleAfterAuthenticationBlock() {
        val blocked = enabled.copy(blocked = "认证失败", revision = 11)
        assertEquals(ScheduleAction.CANCEL, PollingSchedulePolicy.decide(enabled, blocked))
        assertEquals(
            ScheduleAction.ENABLE,
            PollingSchedulePolicy.decide(
                blocked,
                blocked.copy(blocked = "", revision = 12),
            ),
        )
    }

    @Test
    fun resultRefreshDoesNotPostponeNextPeriodicRun() {
        assertEquals(
            ScheduleAction.KEEP,
            PollingSchedulePolicy.decide(
                enabled,
                enabled.copy(lastSuccessMs = 1000, revision = 12),
            ),
        )
    }

    @Test
    fun newerPeriodOrConstraintsUpdateTheSchedule() {
        for (config in
            listOf(
                enabled.config.copy(intervalMinutes = 30),
                enabled.config.copy(unmetered = true),
                enabled.config.copy(charging = true),
            )) {
            assertEquals(
                ScheduleAction.ENABLE,
                PollingSchedulePolicy.decide(
                    enabled,
                    enabled.copy(config = config, revision = 11),
                ),
            )
        }
    }

    @Test
    fun staleReplyCannotResumePausedTaskOrCancelNewerTask() {
        val paused = enabled.copy(config = enabled.config.copy(enabled = false), revision = 12)
        assertEquals(ScheduleAction.KEEP, PollingSchedulePolicy.decide(paused, enabled))
        assertEquals(
            ScheduleAction.KEEP,
            PollingSchedulePolicy.decide(
                enabled,
                enabled.copy(config = enabled.config.copy(enabled = false), revision = 9),
            ),
        )
    }
}
