package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.components.BeaconSettingsTransition
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.SettingsScreen
import io.github.beacon.app.ui.theme.BeaconTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun navigation(style: BeaconUiStyle) {
        lateinit var motion: SeekableTransitionState<SettingsScreen>
        lateinit var scope: CoroutineScope
        var screen by mutableStateOf(SettingsScreen.HOME)
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    motion = remember { SeekableTransitionState(SettingsScreen.HOME) }
                    scope = rememberCoroutineScope()
                    val transition = rememberTransition(motion, label = "Test settings")
                    LaunchedEffect(screen) { motion.animateTo(screen) }
                    Column(Modifier.fillMaxSize()) {
                        BeaconSettingsTransition(transition) {
                            Box(
                                Modifier.fillMaxWidth()
                                    .height(if (it == SettingsScreen.HOME) 160.dp else 80.dp)
                                    .testTag("header-$it")
                            ) {
                                BeaconText(it.name)
                            }
                        }
                        BeaconSettingsTransition(transition) {
                            Box(Modifier.fillMaxWidth().height(250.dp).testTag("body-$it")) {
                                BeaconText(it.name)
                            }
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnUiThread { screen = SettingsScreen.CONNECTION }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(150)
        val header = rule.onNodeWithTag("header-CONNECTION").fetchSemanticsNode().boundsInRoot
        val body = rule.onNodeWithTag("body-CONNECTION").fetchSemanticsNode().boundsInRoot
        assertTrue("Child slides from trailing edge", body.left > 0)
        assertEquals("Header and body move together", header.left, body.left, 2f)
        val density = rule.activity.resources.displayMetrics.density
        assertTrue(
            "Header height converges during entry instead of jumping on completion",
            body.top > 80 * density && body.top < 160 * density,
        )
        rule.mainClock.advanceTimeBy(700)
        assertEquals(
            0f,
            rule.onNodeWithTag("body-CONNECTION").fetchSemanticsNode().boundsInRoot.left,
            1f,
        )
        rule.runOnUiThread { scope.launch { motion.seekTo(.5f, SettingsScreen.HOME) } }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        val seeking = rule.onNodeWithTag("body-CONNECTION").fetchSemanticsNode().boundsInRoot
        assertTrue("Back gesture moves child", seeking.left > 0)
        rule.runOnUiThread { scope.launch { motion.animateTo(SettingsScreen.CONNECTION) } }
        rule.mainClock.advanceTimeBy(700)
        rule.onNodeWithTag("body-CONNECTION").assertExists()
        rule.runOnUiThread { screen = SettingsScreen.HOME }
        rule.mainClock.advanceTimeBy(700)
        rule.onNodeWithTag("body-HOME").assertExists()
        rule.onNodeWithTag("body-CONNECTION").assertDoesNotExist()
        rule.mainClock.autoAdvance = true
    }

    @Test fun settingsEntryBackAndCancelledGestureMiuix() = navigation(BeaconUiStyle.MIUIX)

    @Test fun settingsEntryBackAndCancelledGestureMaterial() = navigation(BeaconUiStyle.MATERIAL3)
}
