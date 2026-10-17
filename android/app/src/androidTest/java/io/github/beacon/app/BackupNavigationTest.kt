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
        val settingsTab = hasContentDescription("设置")
        rule.waitUntil(10_000) {
            rule.onAllNodes(settingsTab).fetchSemanticsNodes().isNotEmpty()
        }
        // The settings entry is a toolbar icon in both styles.
        rule.onNode(settingsTab).performClick()
        rule.onNodeWithText("设置备份").assertDoesNotExist()
        rule.onNodeWithText("消息导出与迁移").assertDoesNotExist()
        val verticalList =
            SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
            )
        rule.onAllNodes(verticalList).onLast().performScrollToNode(hasText("备份与迁移"))
        rule.onNodeWithText("备份与迁移").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("设置备份").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("设置备份").performScrollTo()
        rule.onNodeWithText("设置备份").assertIsDisplayed()
        rule.onNodeWithText("消息导出与迁移").performScrollTo().assertIsDisplayed()
        pressBack()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("消息导出与迁移").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("消息导出与迁移").assertDoesNotExist()
        rule.onNodeWithContentDescription("返回消息列表").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithContentDescription("设置").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("设置").assertIsDisplayed()
    }
}
