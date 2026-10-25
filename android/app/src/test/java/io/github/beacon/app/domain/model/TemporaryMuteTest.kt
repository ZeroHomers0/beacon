package io.github.beacon.app.domain.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TemporaryMuteTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    @Test fun morningIsTheNextLocalEightOClockAndExpiryIsExclusive() {
        val before=Instant.parse("2026-10-25T23:00:00Z").toEpochMilli()
        val at=Instant.parse("2026-10-26T00:00:00Z").toEpochMilli()
        assertEquals(at,TemporaryMute.until(-1,before,zone))
        assertEquals(at+86_400_000,TemporaryMute.until(-1,at,zone))
        assertTrue(TemporaryMute.active(at,at-1));assertFalse(TemporaryMute.active(at,at))
    }
    @Test fun localScopeNeverAffectsAnotherSourceOrApplication() {
        val rules=MuteRules(mapOf(MuteScope(MuteKind.APPLICATION,"source","app") to 100))
        assertTrue(rules.muted("source","app",nowMs=50))
        assertFalse(rules.muted("another","app",nowMs=50))
        assertFalse(rules.muted("source","another",nowMs=50))
        assertFalse(rules.muted("source","app",nowMs=100))
    }
    @Test fun topicAndGlobalRulesCombineWithoutOverwritingEachOther() {
        val topic=MuteScope(MuteKind.TOPIC,"source",":quota")
        assertTrue(MuteRules(mapOf(topic to 100)).muted("source",topic=":quota",nowMs=50))
        assertFalse(MuteRules(mapOf(topic to 100)).muted("source",topic=":other",nowMs=50))
        assertTrue(MuteRules(mapOf(MuteScope.GLOBAL to 100)).muted("any",nowMs=50))
    }
}
