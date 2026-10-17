package io.github.beacon.app

import androidx.activity.BackEventCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.BeaconViewModel
import io.github.beacon.app.ui.components.SettingsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsPagePredictiveBackTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun navigation(style: BeaconUiStyle, shortWindow: Boolean = false) {
        val model = ViewModelProvider(rule.activity)[BeaconViewModel::class.java]
        val originalStyle = model.uiState.uiStyle
        val dispatcher = rule.activity.onBackPressedDispatcher
        val label = rule.activity.getString(R.string.settings_reception_page_title)
        try {
            rule.waitUntil(15_000) {
                model.state.value.runtimeConnected && !model.state.value.messageBusy
            }
            rule.runOnUiThread {
                model.uiState.closeDetail(model)
                model.uiState.uiStyle = style
                model.uiState.settingsScreen = SettingsScreen.HOME
                model.uiState.selectionMode = false
                model.uiState.filtersOpen = false
                model.uiState.page = 1
                if (shortWindow) rule.activity.showNavigationTestWindow(model)
            }
            rule.waitForIdle()
            val row = rule.onNodeWithText(label, useUnmergedTree = true)
            row.performScrollTo().assertIsDisplayed()
            val initial = row.getUnclippedBoundsInRoot()
            rule.runOnUiThread {
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            }
            rule.waitForIdle()
            rule.runOnUiThread {
                dispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 200f, .4f, BackEventCompat.EDGE_LEFT))
            }
            rule.waitForIdle()
            val seeking = row.getUnclippedBoundsInRoot()
            assertTrue("Settings follows the gesture", seeking.left.value > initial.left.value + 20f)
            assertEquals("A preview does not change navigation", 1, model.uiState.page)
            rule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
            rule.waitForIdle()
            row.assertIsDisplayed()
            assertEquals(1, model.uiState.page)
            assertEquals(initial.left.value, row.getUnclippedBoundsInRoot().left.value, 1f)

            // Cross the pager's halfway anchor from the other screen edge before committing.
            rule.runOnUiThread {
                dispatcher.dispatchOnBackStarted(BackEventCompat(400f, 200f, 0f, BackEventCompat.EDGE_RIGHT))
            }
            rule.waitForIdle()
            rule.runOnUiThread {
                dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 200f, if (shortWindow) .95f else .7f, BackEventCompat.EDGE_RIGHT))
            }
            rule.waitForIdle()
            assertEquals(1, model.uiState.page)
            val messages = rule.activity.getString(R.string.ui_nav_messages_title)
            val preview = if (shortWindow) rule.onNodeWithText(messages, useUnmergedTree = true).getUnclippedBoundsInRoot() else null
            rule.runOnUiThread { dispatcher.onBackPressed() }
            rule.waitUntil(15_000) { model.uiState.page == 0 }
            rule.waitForIdle()
            row.assertIsNotDisplayed()
            if (preview != null) {
                val settled = rule.onNodeWithText(messages, useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertTrue("The compact message title does not jump on commit", kotlin.math.abs(preview.top.value - settled.top.value) < 12f)
            }
            assertEquals(SettingsScreen.HOME, model.uiState.settingsScreen)
        } finally {
            rule.runOnUiThread {
                model.uiState.uiStyle = originalStyle
                model.uiState.page = 0
            }
        }
    }

    @Test fun settingsPageGestureCancelAndCommitMiuix() = navigation(BeaconUiStyle.MIUIX)

    @Test fun settingsPageGestureCancelAndCommitMaterial() = navigation(BeaconUiStyle.MATERIAL3)
    @Test fun settingsShortWindowGestureMiuix() = navigation(BeaconUiStyle.MIUIX, shortWindow = true)
    @Test fun settingsShortWindowGestureMaterial() = navigation(BeaconUiStyle.MATERIAL3, shortWindow = true)
    private fun secondaryPage(style: BeaconUiStyle) {
        val model = ViewModelProvider(rule.activity)[BeaconViewModel::class.java]
        val originalStyle = model.uiState.uiStyle
        try {
            rule.waitUntil(15_000) { model.state.value.runtimeConnected && !model.state.value.messageBusy }
            rule.runOnUiThread {
                model.uiState.closeDetail(model)
                model.uiState.uiStyle = style
                model.uiState.page = 1
                model.uiState.navigateSettings(SettingsScreen.HOME)
                model.uiState.navigateSettings(SettingsScreen.STORAGE)
            }
            rule.waitForIdle()
            rule.waitUntil(15_000) { model.state.value.cleanupPreview != null && !model.state.value.messageBusy }
            val title = rule.activity.getString(R.string.settings_storage_title)
            val body = rule.onNodeWithText(rule.activity.getString(R.string.settings_storage_title), useUnmergedTree = true)
            val initialBody = body.getUnclippedBoundsInRoot()
            val initialHeader = rule.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val dispatcher = rule.activity.onBackPressedDispatcher
            rule.runOnUiThread { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT)) }
            rule.waitForIdle()
            rule.runOnUiThread { dispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 200f, .4f, BackEventCompat.EDGE_LEFT)) }
            rule.waitForIdle()
            val density = rule.activity.resources.displayMetrics.density
            val header = rule.onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes()
                .first { it.boundsInRoot.height > 0 && it.boundsInRoot.top < (initialHeader.bottom.value + 10f) * density }
            val shift = body.getUnclippedBoundsInRoot().left.value - initialBody.left.value
            assertTrue(shift > 20f)
            assertEquals("Secondary-page header and content move together", shift, header.boundsInRoot.left / density - initialHeader.left.value, 2f)
            rule.assertSmoothBackCancellation(
                initialBody.left.value, initialBody.left.value + shift,
                cancel = { rule.runOnUiThread { dispatcher.dispatchOnBackCancelled() } },
                position = { body.getUnclippedBoundsInRoot().left.value },
            )
            rule.waitForIdle()
            assertEquals(SettingsScreen.STORAGE, model.uiState.settingsScreen)
            assertEquals(initialBody.left.value, body.getUnclippedBoundsInRoot().left.value, 1f)
            rule.runOnUiThread { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT)) }
            rule.waitForIdle()
            rule.runOnUiThread { dispatcher.dispatchOnBackProgressed(BackEventCompat(240f, 200f, .7f, BackEventCompat.EDGE_LEFT)) }
            rule.waitForIdle()
            rule.runOnUiThread { dispatcher.onBackPressed() }
            rule.waitForIdle()
            assertEquals(SettingsScreen.HOME, model.uiState.settingsScreen)
        } finally {
            rule.runOnUiThread {
                model.uiState.uiStyle = originalStyle
                model.uiState.navigateSettings(SettingsScreen.HOME)
                model.uiState.page = 0
            }
        }
    }
    @Test fun secondaryHeaderAndBodyMiuix() = secondaryPage(BeaconUiStyle.MIUIX)
    @Test fun secondaryHeaderAndBodyMaterial() = secondaryPage(BeaconUiStyle.MATERIAL3)

}

