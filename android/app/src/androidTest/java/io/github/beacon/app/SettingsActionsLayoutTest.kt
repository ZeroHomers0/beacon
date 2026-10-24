package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
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
        assertEquals("Peers must have equal widths", refresh.right - refresh.left, clean.right - clean.left)
        assertEquals("Peers must have equal heights", refresh.bottom - refresh.top, clean.bottom - clean.top)
        if (scale == 1f) assertEquals(refresh.top, clean.top)
        else {
            assertEquals(container.right - container.left, clean.right - clean.left)
            assertTrue(clean.top >= refresh.bottom)
        }
        assertTrue(refresh.left >= container.left && refresh.right <= container.right)
        assertTrue(clean.left >= container.left && clean.right <= container.right)
        assertTrue("Actions must never overlap", clean.left >= refresh.right || clean.top >= refresh.bottom)
    }

    private fun verifySingleAction(style: BeaconUiStyle) {
        val enabled = mutableStateOf(true)
        var clicks = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    Column(Modifier.width(280.dp).testTag("single-container")) {
                        SettingsActions {
                            SettingsPrimaryButton("允许通知", enabled = enabled.value) { clicks++ }
                        }
                        SettingsActions {
                            SettingsTextAction("补发未展示的通知", BeaconIcons.Messages, enabled = enabled.value) { clicks++ }
                        }
                    }
                }
            }
        }
        val allow = rule.onNode(hasText("允许通知") and hasClickAction())
        val resend = rule.onNode(hasText("补发未展示的通知") and hasClickAction())
        val before = listOf(allow, resend).map { it.getUnclippedBoundsInRoot() }
        val container = rule.onNodeWithTag("single-container").getUnclippedBoundsInRoot()
        before.forEach { assertEquals(container.left, it.left); assertEquals(container.right, it.right) }
        allow.performClick()
        rule.waitUntil { clicks == 1 }
        rule.runOnIdle { enabled.value = false }
        listOf(allow, resend).forEachIndexed { index, node ->
            node.assertIsNotEnabled()
            assertEquals(before[index], node.getUnclippedBoundsInRoot())
        }
    }

    @Test fun materialSingleActionsKeepFullWidth() = verifySingleAction(BeaconUiStyle.MATERIAL3)
    @Test fun miuixSingleActionsKeepFullWidth() = verifySingleAction(BeaconUiStyle.MIUIX)

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

    private fun verifyRefreshOwnership(style: BeaconUiStyle) {
        val loading = mutableStateOf(false)
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    Row {
                        BeaconRefreshAction("刷新一", !loading.value, loading.value) { loading.value = true }
                        BeaconRefreshAction("刷新二", !loading.value, loading.value) { loading.value = true }
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
        val spinning = SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
        rule.runOnIdle { loading.value = true }
        rule.mainClock.advanceTimeBy(32)
        rule.onAllNodes(spinning).assertCountEquals(0)
        rule.runOnIdle { loading.value = false }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithContentDescription("刷新一").performClick()
        rule.mainClock.advanceTimeBy(32)
        rule.onAllNodes(spinning).assertCountEquals(1)
        rule.onNode(hasContentDescription("刷新一") and spinning).assertExists()
        rule.runOnIdle { loading.value = false }
        rule.mainClock.advanceTimeBy(400)
        rule.onAllNodes(spinning).assertCountEquals(0)
    }

    @Test fun materialOnlyClickedRefreshSpins() = verifyRefreshOwnership(BeaconUiStyle.MATERIAL3)
    @Test fun miuixOnlyClickedRefreshSpins() = verifyRefreshOwnership(BeaconUiStyle.MIUIX)

    @Test fun materialToolFeedback() = verifyTool(BeaconUiStyle.MATERIAL3)
    @Test fun miuixToolFeedback() = verifyTool(BeaconUiStyle.MIUIX)

    @Test fun materialNormal() = verify(BeaconUiStyle.MATERIAL3, 1f)
    @Test fun materialLargeFont() = verify(BeaconUiStyle.MATERIAL3, 2f)
    @Test fun miuixNormal() = verify(BeaconUiStyle.MIUIX, 1f)
    @Test fun miuixLargeFont() = verify(BeaconUiStyle.MIUIX, 2f)
}
