package io.github.beacon.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the settings key/value row against the fixed-fraction regression.
 *
 * The row used to hand the key 0.38 of its width whatever the key measured, so an 11-character key
 * was asked to wrap inside a 314px column and left one or two characters stranded on the next line.
 * These cases pin the decision to the text that actually has to fit.
 */
class DiagnosticRowLayoutTest {
    /**
     * Measured on a 360dp screen at default font: card inner width with 16dp between the columns.
     */
    private val rowWidth = 827
    private val gap = 42

    @Test
    fun longKeyAndShortValueStillShareOneLine() {
        // 连接尝试 / 连续重试 measures 324px; the old 0.38 column was 314px, so it wrapped.
        assertTrue(diagnosticRowFits(324, 77, gap, rowWidth))
    }

    @Test
    fun shortKeyAndShortValueShareOneLine() {
        assertTrue(diagnosticRowFits(219, 111, gap, rowWidth))
    }

    @Test
    fun longValueFallsBackToStackedRatherThanWrapping() {
        // 系统限制后台启动，打开应用后重试 measures about 636px next to a 255px key.
        assertFalse(diagnosticRowFits(255, 636, gap, rowWidth))
    }

    @Test
    fun pairThatOverflowsOnlyPartiallyStillStacks() {
        assertFalse(diagnosticRowFits(600, 300, gap, rowWidth))
        assertTrue(diagnosticRowFits(400, 300, gap, rowWidth))
    }

    @Test
    fun toleranceCoversRoundingAtTheExactBoundary() {
        val exact = rowWidth - gap - DIAGNOSTIC_ROW_TOLERANCE_PX
        assertTrue(diagnosticRowFits(exact, 0, gap, rowWidth))
        assertFalse(diagnosticRowFits(exact + 1, 0, gap, rowWidth))
    }

    @Test
    fun widerRowsKeepShortPairsOnOneLine() {
        // A tablet-class row must not start stacking pairs that comfortably fit.
        assertTrue(diagnosticRowFits(324, 636, gap, 1600))
    }
}
