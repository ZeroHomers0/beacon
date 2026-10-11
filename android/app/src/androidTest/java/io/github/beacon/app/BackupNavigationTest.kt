package io.github.beacon.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import org.junit.Rule
import org.junit.Test

class BackupNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun exportsLiveInTheirOwnSettingsPage() {
        val settingsTab = hasText("设置") or hasContentDescription("设置")
        rule.waitUntil(10_000) {
            rule.onAllNodes(settingsTab).fetchSemanticsNodes().isNotEmpty()
        }
        // MIUIX navigation uses pointer input; tap its label rather than requiring OnClick.
        rule.onAllNodes(settingsTab).onLast().performTouchInput { click() }
        rule.onNodeWithText("设置备份").assertDoesNotExist()
        rule.onNodeWithText("消息导出与迁移").assertDoesNotExist()
        val verticalList =
            SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
            )
        rule.onAllNodes(verticalList).onLast().performScrollToNode(hasText("备份与迁移"))
        rule.onNodeWithText("备份与迁移").performClick()
        rule.onNodeWithText("设置备份").assertIsDisplayed()
        rule.onNodeWithText("消息导出与迁移").performScrollTo().assertIsDisplayed()
        pressBack()
        rule.onNodeWithText("消息导出与迁移").assertDoesNotExist()
    }
}
