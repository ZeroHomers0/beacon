package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import io.github.beacon.app.data.local.UiStyleStore
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.*
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MiuixFeaturesTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun officialNavigationHonorsDraftGuardAndAncestorBackStack() {
        lateinit var ui: BeaconUiState
        rule.runOnUiThread {
            ui = BeaconUiState(SavedStateHandle(), UiStyleStore(rule.activity))
            rule.activity.setContent {
                BeaconTheme(BeaconUiStyle.MIUIX) {
                    BeaconSettingsNavHost(ui) { screen ->
                        Box(Modifier.fillMaxSize().testTag("route-$screen")) { BeaconText(screen.name) }
                    }
                }
            }
        }
        rule.runOnIdle { ui.navigateSettings(SettingsScreen.SERVER_ADMIN) }
        rule.onNodeWithTag("route-SERVER_ADMIN").assertExists()
        rule.runOnIdle { ui.navigateSettings(SettingsScreen.SERVER_DEVICES) }
        rule.onNodeWithTag("route-SERVER_DEVICES").assertExists()
        rule.runOnIdle { ui.shouldConfirmExit = { true }; ui.navigateSettingsAncestor(SettingsScreen.HOME) }
        rule.runOnIdle { assertEquals(SettingsScreen.SERVER_DEVICES, ui.settingsScreen); assertNotNull(ui.pendingNavigation) }
        rule.onNodeWithTag("route-SERVER_DEVICES").assertExists()
        rule.runOnIdle { ui.discardAndContinue() }
        rule.onNodeWithTag("route-HOME").assertExists()
        rule.runOnIdle { assertTrue(ui.settingsHistory.isEmpty()); ui.shouldConfirmExit = { false } }
        rule.runOnIdle { ui.navigateSettings(SettingsScreen.CONNECTION) }
        rule.onNodeWithTag("route-CONNECTION").assertExists()
        val dispatcher = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread { dispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 200f, 0f, 0)) }
        rule.runOnUiThread { dispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(160f, 200f, .4f, 0)) }
        rule.waitForIdle()
        assertTrue(rule.onNodeWithTag("route-CONNECTION").getUnclippedBoundsInRoot().left.value > 0f)
        rule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        rule.onNodeWithTag("route-CONNECTION").assertIsDisplayed()
        rule.runOnIdle { assertEquals(SettingsScreen.CONNECTION, ui.settingsScreen) }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("route-HOME").assertExists()
    }

    @Test fun toolbarLongPressExplainsActionWithoutClicking() {
        var clicks = 0
        rule.runOnUiThread { rule.activity.setContent {
            BeaconTheme(BeaconUiStyle.MIUIX) {
                BeaconScaffold(topBar = {}, bottomBar = {}) {
                    Box(Modifier.fillMaxSize().padding(it)) {
                        BeaconActionIcon(BeaconIcons.Settings, "操作说明") { clicks++ }
                    }
                }
            }
        } }
        rule.onNodeWithContentDescription("操作说明").performTouchInput { longClick() }
        rule.onNodeWithText("操作说明").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, clicks) }
    }
}
