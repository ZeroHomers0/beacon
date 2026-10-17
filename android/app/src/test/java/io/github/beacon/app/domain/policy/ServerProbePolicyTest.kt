package io.github.beacon.app.domain.policy

import org.junit.Assert.*
import org.junit.Test

class ServerProbePolicyTest {
    @Test
    fun automaticProbeKeepsTheDisplayedVerdict() {
        assertFalse(ServerProbePolicy.showsProgress(manual = false, hasVerdict = true))
    }

    @Test
    fun firstProbeShowsProgress() {
        assertTrue(ServerProbePolicy.showsProgress(manual = false, hasVerdict = false))
    }

    @Test
    fun manualProbeAlwaysShowsProgress() {
        assertTrue(ServerProbePolicy.showsProgress(manual = true, hasVerdict = true))
        assertTrue(ServerProbePolicy.showsProgress(manual = true, hasVerdict = false))
    }
}
