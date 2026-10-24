package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QuietDaysSelectionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        try {
            for (scale in listOf(1f, 2f)) {
                val form = QuietHoursFormState().apply { reset(QuietHours(enabled = true, weekdays = 127)); loaded = true }
                rule.runOnUiThread {
                    rule.activity.setContent {
                        BeaconTheme(style) {
                            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    QuietHoursPanel(RuntimeState(runtimeConnected = true), repository, form, scheduleOnly = true)
                                }
                            }
                        }
                    }
                }
                val labels = rule.activity.getString(R.string.quiet_weekdays).split("、")
                val day = rule.onNodeWithText(labels[0])
                day.performScrollTo().assertIsSelected().assertIsEnabled()
                val before = day.getUnclippedBoundsInRoot()
                val pixels = day.captureToImage().toPixelMap()
                val color = pixels[pixels.width / 2, pixels.height - 4]
                day.performClick()
                rule.waitUntil { form.draft.weekdays == 126 }
                day.assertIsNotSelected()
                rule.onNodeWithText(rule.activity.getString(R.string.settings_custom_days)).assertIsSelected()
                assertEquals(before, day.getUnclippedBoundsInRoot())
                val after = day.captureToImage().toPixelMap()
                assertNotEquals("Selection must change visible fill", color, after[after.width / 2, after.height - 4])
                rule.onNodeWithText(rule.activity.getString(R.string.settings_work_days)).performScrollTo().performClick()
                rule.waitUntil { form.draft.weekdays == 31 }
                rule.onNodeWithText(rule.activity.getString(R.string.settings_work_days)).assertIsSelected()
                rule.onNodeWithText(labels[5]).performScrollTo().assertIsNotSelected()
                rule.onNodeWithText(labels[5]).performClick()
                rule.waitUntil { form.draft.weekdays == 63 }
                rule.onNodeWithText(labels[5]).assertIsSelected()
                rule.runOnIdle { assertEquals(63, form.pending().weekdays) }
            }
        } finally { rule.runOnUiThread { repository.close() } }
    }
    @Test fun miuixDaysHaveVisibleSelectionAndDirectEditing() = verify(BeaconUiStyle.MIUIX)
    @Test fun materialDaysHaveVisibleSelectionAndDirectEditing() = verify(BeaconUiStyle.MATERIAL3)
}
