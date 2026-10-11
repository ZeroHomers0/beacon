package io.github.beacon.app

import android.content.ClipboardManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.diagnostics.*
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RuntimeEventPanelTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        var records = RuntimeEventKind.entries.take(7).map { RuntimeEvent(1000L + it.wireId, it) }
        var clears = 0
        var rejectClear = false
        val actions =
            object : RuntimeActions by repository {
                override fun eventLog(
                    onFailure: () -> Unit,
                    onComplete: (List<RuntimeEvent>) -> Unit,
                ) {
                    onComplete(records)
                }

                override fun clearEventLog(onFailure: () -> Unit, onComplete: () -> Unit) {
                    if (rejectClear) onFailure()
                    else {
                        records = emptyList()
                        clears++
                        onComplete()
                    }
                }
            }
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            RuntimeEventPanel(true, actions)
                        }
                    }
                }
            }
            rule.onNodeWithText("工作进程启动").assertDoesNotExist()
            rule.onNodeWithText("复制记录").performScrollTo().performClick()
            rule.runOnUiThread {
                val text =
                    rule.activity
                        .getSystemService(ClipboardManager::class.java)
                        .primaryClip!!
                        .getItemAt(0)
                        .text
                        .toString()
                assertTrue(text.contains("工作进程启动"))
                assertTrue(text.contains("1970-01-01T"))
                assertFalse(text.contains("http://"))
            }
            rule.onNodeWithText("查看全部").performScrollTo().performClick()
            rule.onNodeWithText("关闭").performClick()
            rule.onNodeWithText("清空事件记录").performScrollTo().performClick()
            rule.onNodeWithText("取消").performClick()
            assertEquals(0, clears)
            rejectClear = true
            rule.onNodeWithText("清空事件记录").performScrollTo().performClick()
            rule.onNodeWithText("确认清空").performClick()
            assertEquals(7, records.size)
            assertEquals(0, clears)
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithText("即时连接成功").performScrollTo().assertIsDisplayed()
            rejectClear = false
            rule.onNodeWithText("清空事件记录").performScrollTo().performClick()
            rule.onNodeWithText("确认清空").performClick()
            assertEquals(1, clears)
            rule.onNodeWithText("暂无事件记录").performScrollTo().assertIsDisplayed()
        } finally {
            rule.runOnUiThread { repository.close() }
        }
    }

    @Test fun materialEventCopyAndConfirmedClear() = verify(BeaconUiStyle.MATERIAL3)

    @Test fun miuixEventCopyAndConfirmedClear() = verify(BeaconUiStyle.MIUIX)
}
