package io.github.beacon.app

import android.content.ClipboardManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.messages.MessageDetailScreen
import io.github.beacon.app.feature.messages.MessagesPage
import io.github.beacon.app.feature.settings.PushExamplesPanel
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.theme.BeaconTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MessageConvenienceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        var shared = 0
        var reminded = 0
        val repository = RuntimeRepository()
        val address = "https://example.com/" + "a".repeat(64)
        val actions =
            object : RuntimeActions by repository {
                override fun copyPushKey(onReady: (String) -> Unit) {
                    onReady("a".repeat(64))
                }
            }
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        MessageDetailScreen(
                            IncomingMessage("server", "id", "标题", "正文 https://example.com/page"),
                            Modifier.fillMaxSize(),
                            {},
                            {},
                            false,
                            {},
                            onShare = { shared++ },
                            onRemind = { reminded++ },
                        )
                    }
                }
            }
            rule.onNodeWithContentDescription("详情更多操作").performClick()
            rule.onNodeWithText("分享消息").performClick()
            assertEquals(1, shared)
            rule.onNodeWithContentDescription("详情更多操作").performClick()
            rule.onNodeWithText("重新提醒").performClick()
            assertEquals(1, reminded)
            rule.onNodeWithText("example.com").performScrollTo().performClick()
            rule.onNodeWithText("打开网页链接？").assertIsDisplayed()
            rule.onNodeWithText("打开浏览器").assertIsDisplayed()
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithText("打开网页链接？").assertDoesNotExist()
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) { PushExamplesPanel(actions, true, "https://example.com") }
                }
            }
            rule.onNodeWithText("复制 curl 示例").assertDoesNotExist()
            rule.onNodeWithText(rule.activity.getString(R.string.ui_v2_expand_examples)).performClick()
            rule.onNodeWithText("复制 curl 示例").performClick()
            val clipboard = rule.activity.getSystemService(ClipboardManager::class.java)
            rule.runOnUiThread {
                assertTrue(
                    clipboard.primaryClip!!
                        .getItemAt(0)
                        .text
                        .toString()
                        .startsWith("curl --fail-with-body")
                )
            }
            rule.onNodeWithText("复制 PowerShell 示例").performClick()
            rule.runOnUiThread {
                assertTrue(
                    clipboard.primaryClip!!
                        .getItemAt(0)
                        .text
                        .toString()
                        .startsWith("Invoke-RestMethod")
                )
            }
            rule.onNodeWithText("复制 JSON curl 示例").performClick()
            rule.runOnUiThread {
                assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().contains("--data"))
            }
            rule.onNodeWithText("复制 JSON PowerShell 示例").performClick()
            rule.runOnUiThread {
                assertTrue(
                    clipboard.primaryClip!!.getItemAt(0).text.toString().contains("UTF8.GetBytes")
                )
            }
            val today =
                LocalDate.now(ZoneId.systemDefault())
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        MessagesPage(
                            rememberLazyListState(),
                            Modifier.fillMaxSize(),
                            PaddingValues(8.dp),
                            RuntimeState(
                                messages =
                                    listOf(
                                        IncomingMessage(
                                            "server",
                                            "1",
                                            "今天消息",
                                            "body",
                                            receivedAtMs = today + 1000,
                                        ),
                                        IncomingMessage(
                                            "server",
                                            "2",
                                            "昨天消息",
                                            "body",
                                            receivedAtMs = today - 1000,
                                        ),
                                    )
                            ),
                            actions,
                            "",
                            MessageReadFilter.ALL,
                            false,
                            emptyList(),
                            "",
                            onSearch = {},
                            onApplyFilter = { _, _ -> },
                            onConnectionSettings = {},
                            onEnterSelection = {},
                            onSelectionChange = {},
                            onSelectionHint = {},
                            onCopyText = { _, _ -> },
                        )
                    }
                }
            }
            rule.onNodeWithText(LocalDate.now(ZoneId.systemDefault()).toString()).assertIsDisplayed()
            rule.onNodeWithText(LocalDate.now(ZoneId.systemDefault()).minusDays(1).toString()).performScrollTo().assertIsDisplayed()
        } finally {
            rule.runOnUiThread {
                repository.close()
                rule.activity.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
            }
        }
    }

    @Test fun materialConvenienceFlows() = verify(BeaconUiStyle.MATERIAL3)

    @Test fun miuixConvenienceFlows() = verify(BeaconUiStyle.MIUIX)
}
