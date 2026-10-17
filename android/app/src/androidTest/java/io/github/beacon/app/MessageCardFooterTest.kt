package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.policy.DisplayTime
import io.github.beacon.app.ui.components.MessageItem
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MessageCardFooterTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun verify(style: BeaconUiStyle, scale: Float) {
        val app = "很长的应用名称用于验证单行标签"
        val group = "很长的分组名称用于验证单行标签"
        val time = 1792022462000L
        var apps = 0
        var groups = 0
        rule.runOnUiThread { rule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                BeaconTheme(style) {
                    Column(Modifier.width(320.dp).padding(8.dp)) {
                        MessageItem(
                            IncomingMessage("synthetic", "id", "标题", "正文", createdAtMs = time, applicationId = "app", applicationName = app, group = group),
                            false, false, {}, {}, {}, false, {}, {}, {},
                            onFilterApplication = { apps++ }, onFilterGroup = { groups++ },
                        )
                    }
                }
            }
        } }
        val clock = rule.onNodeWithText(DisplayTime.clock(time), useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        rule.onNodeWithText(DisplayTime.format(time)).assertDoesNotExist()
        val application = rule.onNodeWithContentDescription(app).assertIsDisplayed()
        val grouping = rule.onNodeWithContentDescription(group).assertIsDisplayed()
        val a = application.getUnclippedBoundsInRoot()
        val g = grouping.getUnclippedBoundsInRoot()
        val body = rule.onNodeWithText("正文", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(a.top >= body.bottom)
        assertEquals(a.top.value, g.top.value, 1f)
        assertTrue(a.right <= g.left)
        assertTrue(clock.right < a.left)
        assertTrue(kotlin.math.abs((clock.top + clock.bottom).value / 2 - (a.top + a.bottom).value / 2) < 2)
        rule.onNodeWithText("…").assertDoesNotExist()
        application.performClick(); grouping.performClick()
        assertEquals(1, apps); assertEquals(1, groups)
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = java.io.File(rule.activity.getExternalFilesDir(null), "footer-$style-$scale.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val descriptor = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/${file.name}")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
    private fun asymmetric(style: BeaconUiStyle) {
        val app = "这是一个较长的应用名称"
        rule.runOnUiThread { rule.activity.setContent {
            BeaconTheme(style) { Column(Modifier.width(320.dp).padding(8.dp)) {
                io.github.beacon.app.ui.components.MessageCardFooter(
                    IncomingMessage("synthetic", "id", "title", "body", createdAtMs = 1792022462000L, applicationId = "app", applicationName = app, group = "分组"),
                    false, false, {}, {},
                )
            } }
        } }
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText(app, useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
        assertFalse("Short labels must leave unused width to the longer label", results.single().hasVisualOverflow)
        val a = rule.onNodeWithContentDescription(app).getUnclippedBoundsInRoot()
        val g = rule.onNodeWithContentDescription("分组").getUnclippedBoundsInRoot()
        assertTrue((a.right - a.left) > (g.right - g.left))
    }
    @Test fun materialSharesUnusedWidth() = asymmetric(BeaconUiStyle.MATERIAL3)
    @Test fun miuixSharesUnusedWidth() = asymmetric(BeaconUiStyle.MIUIX)
    @Test fun materialFooter() = verify(BeaconUiStyle.MATERIAL3, 1f)
    @Test fun miuixFooter() = verify(BeaconUiStyle.MIUIX, 1f)
    @Test fun materialLargeFontFooter() = verify(BeaconUiStyle.MATERIAL3, 2f)
    @Test fun miuixLargeFontFooter() = verify(BeaconUiStyle.MIUIX, 2f)
}
