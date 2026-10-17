package io.github.beacon.app

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.domain.model.BeaconThemeMode
import io.github.beacon.app.domain.receive.NotificationBatch
import io.github.beacon.app.feature.messages.MessageMarkdownBody
import io.github.beacon.app.runtime.notification.MessageNotifications
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MessageMarkdownTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val body = """
        # 青龙任务报告

        **执行成功**，耗时 *12 秒*。

        - 已完成：3 项
        - 待处理：1 项

        > 建议检查待处理任务。

        ```sh
        echo "a_b * x"
        ```

        [查看报告](https://example.com/report)

        ![任务截图](https://example.com/photo.png)
    """.trimIndent()

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun rendered(): TextView? = textViews(rule.activity.window.decorView)
        .firstOrNull { it.text is Spanned && it.text.contains("青龙任务报告") }

    private fun check(style: BeaconUiStyle, mode: BeaconThemeMode = BeaconThemeMode.LIGHT) {
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style, mode) {
                    Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        MessageMarkdownBody(body)
                    }
                    }
                }
            }
        }
        rule.waitUntil(10_000) {
            var ready = false
            rule.runOnUiThread { ready = rendered() != null }
            ready
        }
        rule.runOnUiThread {
            val view = rendered()!!
            assertTrue(view.isTextSelectable)
            assertFalse(view.text.contains("**"))
            assertTrue(view.text.contains("a_b * x"))
            val text = view.text as Spanned
            val spans = text.getSpans(0, text.length, ClickableSpan::class.java)
            assertEquals(1, spans.size)
            spans.single().onClick(view)
        }
        rule.onNodeWithText("https://example.com/report").assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.action_cancel)).performClick()
        rule.onNodeWithText("加载图片：任务截图").performScrollTo().assertIsDisplayed()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        // Capture the actual AndroidView as well as Compose content.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = java.io.File(rule.activity.getExternalFilesDir(null), "markdown-$style-$mode.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val copy = instrumentation.uiAutomation.executeShellCommand(
            "cp ${output.absolutePath} /data/local/tmp/${output.name}"
        )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
        rule.onNodeWithText("查看原文").performScrollTo().performClick()
        rule.onNodeWithText(body).assertExists()
        rule.onNodeWithText("显示排版").performScrollTo().performClick()
        rule.waitUntil(10_000) {
            var ready = false
            rule.runOnUiThread { ready = rendered() != null }
            ready
        }
    }

    @Test fun materialDetailRendersAndSwitchesToSource() = check(BeaconUiStyle.MATERIAL3)
    @Test fun miuixDetailRendersAndSwitchesToSource() = check(BeaconUiStyle.MIUIX)
    @Test fun darkDetailRendersAndSwitchesToSource() = check(BeaconUiStyle.MATERIAL3, BeaconThemeMode.DARK)

    @Test fun reopeningCachedBodyKeepsLinksBoundToTheNewScreen() {
        check(BeaconUiStyle.MATERIAL3)
        lateinit var previous: ClickableSpan
        rule.runOnUiThread {
            val text = rendered()!!.text as Spanned
            previous = text.getSpans(0, text.length, ClickableSpan::class.java).single()
            rule.activity.setContent { androidx.compose.material3.Text("closed") }
        }
        rule.waitForIdle()
        check(BeaconUiStyle.MIUIX, BeaconThemeMode.DARK)
        rule.runOnUiThread {
            val text = rendered()!!.text as Spanned
            assertNotSame(previous, text.getSpans(0, text.length, ClickableSpan::class.java).single())
        }
    }

    @Test fun systemNotificationUsesPlainSummary() {
        val context = rule.activity
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName, android.Manifest.permission.POST_NOTIFICATIONS,
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val tag = "markdown-test"
        assertTrue(MessageNotifications(context).post(NotificationBatch(tag, 1, false, "报告", body)))
        try {
            rule.waitUntil(5000) { manager.activeNotifications.any { it.tag == tag } }
            val notification = manager.activeNotifications.first { it.tag == tag }.notification
            val summary = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            assertTrue(summary.contains("执行成功"))
            assertFalse(summary.contains("**"))
            assertFalse(summary.contains("https://example.com/report"))
            assertEquals(summary, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        } finally { manager.cancel(tag, 100) }
    }
}
