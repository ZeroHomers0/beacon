package io.github.beacon.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationMotionTest {
    @Test
    fun curveHasStableEndpointsAndNoOvershoot() {
        assertEquals(0f, settingsNavigationEasing.transform(0f), 0f)
        assertEquals(1f, settingsNavigationEasing.transform(1f), 0f)
        var last = 0f
        for (index in 1..100) {
            val value = settingsNavigationEasing.transform(index / 100f)
            assertTrue(value >= last && value <= 1f)
            last = value
        }
    }

    @Test
    fun gestureFollowsFingerRatherThanTweenTime() {
        for (progress in listOf(.01f, .2f, .5f, .8f, .99f)) {
            assertEquals(
                progress,
                settingsNavigationEasing.transform(settingsGestureFraction(progress)),
                .0001f,
            )
        }
        assertEquals(0f, settingsGestureFraction(Float.NaN), 0f)
        assertEquals(0f, settingsGestureFraction(-1f), 0f)
        assertEquals(1f, settingsGestureFraction(2f), 0f)
    }
}
