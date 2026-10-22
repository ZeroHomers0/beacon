package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsActionsLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle, scale: Float) {
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        Column(Modifier.width(280.dp).testTag("actions-container")) {
                            SettingsActions {
                                SettingsTextAction("修改规则", BeaconIcons.Tune) {}
                                SettingsPrimaryButton("立即清理") {}
                            }
                        }
                    }
                }
            }
        }
        val refresh = rule.onNode(hasText("修改规则") and hasClickAction()).getUnclippedBoundsInRoot()
        val clean = rule.onNode(hasText("立即清理") and hasClickAction()).getUnclippedBoundsInRoot()
        val container = rule.onNodeWithTag("actions-container").getUnclippedBoundsInRoot()
        assertTrue(refresh.bottom - refresh.top >= 48.dp)
        assertTrue(clean.bottom - clean.top >= 48.dp)
        assertTrue("Primary action must keep content width", clean.right - clean.left < container.right - container.left)
        assertTrue(refresh.left >= container.left && refresh.right <= container.right)
        assertTrue(clean.left >= container.left && clean.right <= container.right)
        assertTrue("Actions must never overlap", clean.left >= refresh.right || clean.top >= refresh.bottom)
    }

    private fun verifyTool(style: BeaconUiStyle) {
        val complete = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var clicks = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    SettingsToolAction("复制", BeaconIcons.Copy, completed = complete.value,
                        enabled = enabled.value, onClick = { clicks++; complete.value = true })
                }
            }
        }
        val tool = rule.onNode(hasContentDescription("复制") and hasClickAction())
        val bounds = tool.getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left >= 48.dp)
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
        rule.onNodeWithText("复制").assertDoesNotExist()
        tool.performClick()
        rule.waitForIdle()
        tool.assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, clicks); enabled.value = false }
        rule.onNodeWithContentDescription("复制").assertIsNotEnabled()
    }

    @Test fun materialToolFeedback() = verifyTool(BeaconUiStyle.MATERIAL3)
    @Test fun miuixToolFeedback() = verifyTool(BeaconUiStyle.MIUIX)

    @Test fun materialNormal() = verify(BeaconUiStyle.MATERIAL3, 1f)
    @Test fun materialLargeFont() = verify(BeaconUiStyle.MATERIAL3, 2f)
    @Test fun miuixNormal() = verify(BeaconUiStyle.MIUIX, 1f)
    @Test fun miuixLargeFont() = verify(BeaconUiStyle.MIUIX, 2f)
}
