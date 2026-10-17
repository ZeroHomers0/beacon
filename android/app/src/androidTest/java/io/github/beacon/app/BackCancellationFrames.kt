package io.github.beacon.app

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.*

/** End-state checks miss a snap followed by a second entrance: inspect every cancellation frame. */
internal fun ComposeContentTestRule.assertSmoothBackCancellation(
    originalLeft: Float,
    previewLeft: Float,
    cancel: () -> Unit,
    position: () -> Float,
) {
    mainClock.autoAdvance = false
    try {
        cancel()
        waitForIdle()
        var previous = previewLeft
        var settled = false
        repeat(45) { frame ->
            mainClock.advanceTimeByFrame()
            waitForIdle()
            val current = position()
            if (frame == 1)
                assertTrue("Cancellation must animate from the preview instead of snapping home", current > originalLeft + (previewLeft - originalLeft) * .5f)
            assertTrue("Cancelled page must never rebound: frame=$frame, previous=$previous, current=$current", current <= previous + 1f)
            assertTrue("Cancelled page must not overshoot home", current >= originalLeft - 1f)
            if (settled) assertEquals("A settled page must stay still", originalLeft, current, 1f)
            if (kotlin.math.abs(current - originalLeft) <= 1f) settled = true
            previous = current
        }
        assertTrue("Cancellation must finish", settled)
    } finally {
        mainClock.autoAdvance = true
    }
}
