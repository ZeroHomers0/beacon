package io.github.beacon.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.messages.MessageDetailScreen
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic content only; screenshots are device artifacts, never checked-in assets. */
class ArticleDetailPresentationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val article = IncomingMessage(
        "https://example.com", "article", "Beacon 每日简报：把重要信息变成一篇清晰易读的文章",
        """这是一条用于排版验收的示例消息。标题、来源与正文共用页面背景，长文章可以连续阅读。

## 今日重点

正文使用舒适的行距，段落之间保留留白。较长的中文内容不会挤在狭窄卡片中，也不会为了显示完整而缩小字号。

- 接收链路已恢复，离线消息完成同步。
- 桌面小组件会在数据更新后刷新。
- 重要操作仍由用户点击确认后执行。

> 阅读内容时，操作入口保持在顶栏，避免打断正文。

## 构建结果

```text
branch: main
result: success
architecture: arm64-v8a
```

第二部分继续展示正文。下面保留消息相关操作和默认折叠的信息，技术字段不会抢占第一屏。

### 下一步

打开报告查看结果，或者从右上角菜单复制原文、分享消息。""",
        createdAtMs = 1794105000000, receivedAtMs = 1794105060000,
        subtitle = "接收状态、构建结果与今日重点，一次阅读即可了解。",
        applicationId = "ci", applicationName = "CI 构建", group = "每日简报",
        actions = listOf(MessageLinkAction("查看构建报告", "https://example.com/report")),
    )

    private fun capture(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = java.io.File(rule.activity.getExternalFilesDir(null), "$name.png")
        file.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        // Connected-test cleanup uninstalls the APK; retain synthetic screenshots outside its data.
        val copy = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cp ${file.absolutePath} /data/local/tmp/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
    }

    private fun show(style: BeaconUiStyle, mode: BeaconThemeMode, message: IncomingMessage, scale: Float = 1f) {
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    BeaconTheme(style, mode) {
                        MessageDetailScreen(message, Modifier.fillMaxSize(), {}, {}, false, {},
                            onShare = {}, onRemind = {},
                            topBar = { menu ->
                                BeaconTopBar("消息详情", true, rememberBeaconBarBehavior(),
                                    navigationIcon = { BeaconIconButton({}) { Icon(BeaconIcons.Back, "返回") } },
                                    actions = { menu() })
                            })
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("message-detail-header").assertIsDisplayed()
    }

    private fun verify(style: BeaconUiStyle) {
        show(style, BeaconThemeMode.LIGHT, article)
        val header = rule.onNodeWithTag("message-detail-header").fetchSemanticsNode().boundsInRoot
        val body = rule.onNodeWithTag("message-detail-body").fetchSemanticsNode().boundsInRoot
        assertEquals("Article title and body share the reading inset", header.left, body.left, 1f)
        rule.onNodeWithTag("message-detail-raw-content").assertDoesNotExist()
        capture("article-$style-light")
        rule.onNodeWithContentDescription("详情更多操作").performClick()
        rule.onNodeWithText("查看原文").performClick()
        rule.onNodeWithContentDescription("详情更多操作").performClick()
        rule.onNodeWithText("查看排版").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.msg_detail_expand_raw_action))
            .performScrollTo().performClick()
        rule.onNodeWithTag("message-detail-raw-content").assertExists()
        capture("article-$style-footer")
        show(style, BeaconThemeMode.DARK, article.copy(id = "dark"))
        capture("article-$style-dark")
        show(style, BeaconThemeMode.LIGHT, IncomingMessage("synthetic", "short", "服务已恢复", "所有接收任务运行正常。", receivedAtMs = 1794105060000))
        capture("article-$style-short")
        show(style, BeaconThemeMode.LIGHT, article.copy(id="data", title="发布任务进度", subtitle="", body="本次构建正在上传产物。",
            structuredBlocks="""[{"id":"build","kind":"progress","label":"上传进度","value":72,"min":0,"max":100,"unit":"%"},{"id":"jobs","kind":"metric","label":"已完成任务","value":8,"unit":"项"}]"""))
        rule.onNodeWithTag("message-detail-data").assertExists()
        capture("article-$style-data")
        show(style, BeaconThemeMode.LIGHT, article.copy(id="large"), 1.4f)
        capture("article-$style-largefont")
    }

    @Test fun materialArticlePresentation() = verify(BeaconUiStyle.MATERIAL3)
    @Test fun miuixArticlePresentation() = verify(BeaconUiStyle.MIUIX)
}
