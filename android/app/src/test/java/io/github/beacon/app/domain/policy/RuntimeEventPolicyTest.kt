package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.diagnostics.RuntimeEventText
import org.junit.Assert.*
import org.junit.Test

/**
 * Resource copy is resolved by `AndroidRuntimeEventText`, so this JVM test supplies its own
 * [RuntimeEventText]. The assertions check the export format and leak-safety, not the wording.
 */
private object Copy : RuntimeEventText {
    override val title = "Beacon 接收事件记录 v1（UTC，按记录顺序）"
    override val disclaimer = "仅含固定事件类型、结果和时间；最多100条。不含地址、凭证、消息、标识或原始异常。"

    override fun kind(kind: RuntimeEventKind) = kind.name

    override fun outcome(outcome: SyncOutcome) = outcome.name

    override fun failure(failure: RealtimeFailure) =
        if (failure == RealtimeFailure.NONE) null else failure.name
}

class RuntimeEventPolicyTest {
    @Test
    fun historyIsBoundedAndPreservesRecordingOrderAcrossClockChanges() {
        var history = emptyList<RuntimeEvent>()
        repeat(120) {
            history =
                RuntimeEventHistory.append(
                    history,
                    RuntimeEvent(1000L - it, RuntimeEventKind.MANUAL_SYNC, SyncOutcome.OK),
                )
        }
        assertEquals(100, history.size)
        assertEquals(980L, history.first().atMs)
        assertEquals(881L, history.last().atMs)
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeEventHistory.append(history, RuntimeEvent(0, RuntimeEventKind.SERVICE_STARTED))
        }
    }

    @Test
    fun identifiersAreUniqueAndExportContainsOnlyAllowlistedLabels() {
        assertEquals(
            RuntimeEventKind.entries.size,
            RuntimeEventKind.entries.map { it.wireId }.toSet().size,
        )
        assertNull(RuntimeEventKind.fromWireId(-1))
        val events = RuntimeEventKind.entries.map { RuntimeEvent(1, it, SyncOutcome.OK) }
        val report = Copy.export(events)
        assertTrue(report.contains("MANUAL_SYNC · OK"))
        assertTrue(report.contains("1970-01-01 00:00:00"))
        assertFalse(report.contains("null"))
        assertFalse(report.contains("http://"))
        RealtimeFailure.entries.forEach {
            assertTrue(
                Copy.label(RuntimeEvent(1, RuntimeEventKind.REALTIME_RETRYING, failure = it))
                    .isNotBlank()
            )
        }
    }

    @Test
    fun recoveryEventIdsAreAppendedToTheExistingRange() {
        // Installed history is persisted by number, so an id may only ever be appended.
        assertEquals(20, RuntimeEventKind.DEVICE_REVOKED.wireId)
        assertEquals(21, RuntimeEventKind.RECOVERY_AWAITING_UNLOCK.wireId)
        assertEquals(22, RuntimeEventKind.UNEXPECTED_EXIT_RECOVERY.wireId)
        assertEquals(23, RuntimeEventKind.RECOVERY_SUSPENDED.wireId)
        listOf(
                RuntimeEventKind.RECOVERY_AWAITING_UNLOCK,
                RuntimeEventKind.UNEXPECTED_EXIT_RECOVERY,
                RuntimeEventKind.RECOVERY_SUSPENDED,
            )
            .forEach {
                assertEquals(it, RuntimeEventKind.fromWireId(it.wireId))
                assertTrue(Copy.label(RuntimeEvent(1, it)).isNotBlank())
            }
    }

    @Test
    fun retryNeverEnablesPausedPowerSavingOrAuthenticationBlockedReception() {
        val active = PollingState(config = PollingConfig(enabled = true))
        val realtime = ReceivePlan(ReceiveMode.REALTIME)
        assertEquals(
            RealtimeRetryDecision.NO_CONFIGURATION,
            RealtimeRetryPolicy.decide(realtime, active, false),
        )
        assertEquals(
            RealtimeRetryDecision.POWER_SAVING,
            RealtimeRetryPolicy.decide(ReceivePlan(), active, true),
        )
        assertEquals(
            RealtimeRetryDecision.PAUSED,
            RealtimeRetryPolicy.decide(realtime, PollingState(), true),
        )
        assertEquals(
            RealtimeRetryDecision.AUTH_BLOCKED,
            RealtimeRetryPolicy.decide(
                realtime,
                active.copy(blocked = "fixed", lastOutcome = SyncOutcome.AUTH),
                true,
            ),
        )
        assertEquals(
            RealtimeRetryDecision.SETTINGS_BLOCKED,
            RealtimeRetryPolicy.decide(realtime, active.copy(blocked = "fixed"), true),
        )
    }

    @Test
    fun explicitRetryMayRecheckFixedCertificatesButPreservesRulePlan() {
        val rules = ReceivePlan(ReceiveMode.RULES, false, false)
        val state =
            PollingState(
                config = PollingConfig(enabled = true),
                blocked = "TLS",
                lastOutcome = SyncOutcome.TLS,
            )
        assertEquals(RealtimeRetryDecision.ALLOWED, RealtimeRetryPolicy.decide(rules, state, true))
        assertFalse(rules.realtimeOnWifi)
        assertFalse(rules.realtimeWhileCharging)
        assertEquals("TLS", state.blocked)
    }
}
