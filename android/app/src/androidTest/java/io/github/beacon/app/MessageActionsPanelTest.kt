package io.github.beacon.app

import android.content.Intent
import android.content.IntentFilter
import android.os.PatternMatcher
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.domain.model.MessageLinkAction
import io.github.beacon.app.feature.messages.MessageActionsPanel
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MessageActionsPanelTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun show(style: BeaconUiStyle, actions: List<MessageLinkAction>, legacy: String = "") {
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) { Column { MessageActionsPanel(actions, legacy) } }
            }
        }
    }

    @Test
    fun materialNamedActionsShowDestinations() {
        show(
            BeaconUiStyle.MATERIAL3,
            listOf(MessageLinkAction("查看日志", "https://ci.example.com/build/123")),
        )
        rule.onNodeWithText("查看日志").assertIsDisplayed().assertHasClickAction()
        rule.onNodeWithText("https://ci.example.com/build/123").assertIsDisplayed()
    }

    @Test
    fun miuixLegacyLinkHasActionButton() {
        show(BeaconUiStyle.MIUIX, emptyList(), "https://example.com/result")
        rule.onNodeWithText("打开链接").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun invalidActionsAreNotExecutable() {
        show(BeaconUiStyle.MATERIAL3, listOf(MessageLinkAction("运行", "javascript:alert(1)")))
        rule.onNodeWithText("运行").assertDoesNotExist()
        rule.onNodeWithText("消息操作").assertDoesNotExist()
    }

    @Test
    fun namedActionOpensOnlyAfterUserClick() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter =
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
                addDataAuthority("ci.example.com", null)
                addDataPath("/build/123", PatternMatcher.PATTERN_LITERAL)
            }
        val monitor = instrumentation.addMonitor(filter, null, true)
        try {
            show(
                BeaconUiStyle.MATERIAL3,
                listOf(MessageLinkAction("查看日志", "HTTPS://ci.example.com/build/123")),
            )
            rule.waitForIdle()
            assertEquals(0, monitor.hits)
            rule.onNodeWithText("查看日志").performClick()
            rule.waitUntil(5000) { monitor.hits == 1 }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
