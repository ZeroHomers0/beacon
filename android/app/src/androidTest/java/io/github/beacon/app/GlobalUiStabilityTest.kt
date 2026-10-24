package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlobalUiStabilityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun transitions(style: BeaconUiStyle) {
        for (scale in listOf(1f, 2f)) {
            val failed = mutableStateOf(false)
            val longLabel = "确认并清空所有本地消息"
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                            BeaconConfirmDialog("操作确认", "确认后执行此操作", {}, {},
                                confirmText = if (failed.value) longLabel else "继续",
                                statusText = "等待确认", errorText = if (failed.value) "操作失败，请检查网络连接后重试".repeat(4) else null)
                        }
                    }
                }
            }
            val beforeCancel = rule.onNode(hasText("取消") and hasClickAction()).getUnclippedBoundsInRoot()
            val beforeConfirm = rule.onNode(hasText("继续") and hasClickAction()).getUnclippedBoundsInRoot()
            rule.runOnIdle { failed.value = true }
            assertEquals(beforeCancel, rule.onNode(hasText("取消") and hasClickAction()).getUnclippedBoundsInRoot())
            assertEquals(beforeConfirm, rule.onNode(hasText(longLabel) and hasClickAction()).getUnclippedBoundsInRoot())
        }
    }

    private fun metadata(style: BeaconUiStyle) {
        for (scale in listOf(1f, 2f)) {
            val pending = mutableStateOf(false)
            val selecting = mutableStateOf(false)
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                            Column(Modifier.width(300.dp).padding(12.dp)) {
                                MessageItem(IncomingMessage("example.test", "demo", "状态变化检查", "消息正文",
                                    reminderPending = pending.value, applicationId = "app", applicationName = "构建服务", group = "构建通知"),
                                    selecting.value, selecting.value, {}, {}, {}, false, {}, {}, {}, Modifier.testTag("message"))
                            }
                        }
                    }
                }
            }
            val before = rule.onNodeWithTag("message").getUnclippedBoundsInRoot()
            val tags = listOf("构建服务", "构建通知").map { rule.onNodeWithText(it).getUnclippedBoundsInRoot() }
            rule.runOnIdle { pending.value = true }
            assertEquals(before, rule.onNodeWithTag("message").getUnclippedBoundsInRoot())
            listOf("构建服务", "构建通知").forEachIndexed { index, label ->
                assertEquals(tags[index], rule.onNodeWithText(label).getUnclippedBoundsInRoot())
            }
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.msg_reminder_pending_label)).assertExists()
            rule.runOnIdle { selecting.value = true }
            assertEquals(before, rule.onNodeWithTag("message").getUnclippedBoundsInRoot())
        }
    }

    private fun fastRefresh(style: BeaconUiStyle) {
        val background = mutableStateOf(false)
        var calls = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    Row {
                        BeaconRefreshAction("即时刷新", true, false) { calls++ }
                        BeaconRefreshAction("后台状态", !background.value, background.value) {}
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
        val spinning = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
        rule.runOnIdle { background.value = true }
        rule.mainClock.advanceTimeBy(32)
        rule.onAllNodes(spinning).assertCountEquals(0)
        rule.onNodeWithContentDescription("即时刷新").performClick()
        rule.mainClock.advanceTimeBy(32)
        rule.onNode(hasContentDescription("即时刷新") and spinning).assertExists()
        rule.onAllNodes(spinning).assertCountEquals(1)
        rule.mainClock.advanceTimeBy(900)
        rule.onAllNodes(spinning).assertCountEquals(0)
        rule.runOnIdle { assertEquals(1, calls) }
    }

    private fun palette(style: BeaconUiStyle) {
        val samples = (0..7).map { target ->
            generateSequence(0) { it + 1 }.map { "配色样例 $it" }.first {
                io.github.beacon.app.domain.policy.tagToneIndex(it, 0) == target
            }
        }
        for (mode in listOf(BeaconThemeMode.LIGHT, BeaconThemeMode.DARK)) {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style, mode) {
                        val behavior = key(mode) { rememberBeaconBarBehavior() }
                        BeaconScaffold(topBar = { BeaconTopBar("明亮标签", false, behavior, {}, {}) }, bottomBar = {}) { padding ->
                            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                MessageItem(IncomingMessage("example.test", "preview", "消息标签示例", "标签使用明亮底色与清晰文字",
                                    applicationId = "app", applicationName = "构建服务", group = "工作提醒"), false, false,
                                    {}, {}, {}, false, {}, {}, {})
                                for (row in 0..3) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (column in 0..1) {
                                        val slot = row * 2 + column
                                        MessageFilterTag(samples[slot], Modifier.weight(1f).testTag("palette$slot"), toneSlot = 0)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BeaconSummaryPill("共 128 条", BeaconIcons.Mail, true, modifier = Modifier.weight(1f).testTag("summary-selected"), compact = true)
                                    BeaconSummaryPill("8 条未读", BeaconIcons.Messages, modifier = Modifier.weight(1f).testTag("summary-normal"), compact = true)
                                }
                                BeaconActiveFilterChip(onClick = {}, label = { BeaconText("已选筛选条件") },
                                    trailingIcon = { androidx.compose.material3.Icon(BeaconIcons.Close, null, Modifier.size(16.dp)) })
                            }
                        }
                    }
                }
            }
            for (index in 0..7) {
                val pixels = rule.onNodeWithTag("palette$index").captureToImage().toPixelMap()
                val color = pixels[pixels.width / 2, pixels.height - 3]
                assertTrue("Displayed label stays bright in $style / $mode", color.luminance() >= .4f)
            }
            val neutralColors = listOf("summary-selected", "summary-normal").map { tag ->
                val pixels = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
                pixels[pixels.width / 2, pixels.height - 2].also {
                    assertEquals("Statistics stay achromatic", it.red, it.green, .001f)
                    assertEquals("Statistics stay achromatic", it.green, it.blue, .001f)
                }
            }
            assertNotEquals(neutralColors[0], neutralColors[1])
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onNodeWithText("已选筛选条件", useUnmergedTree = true).performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertEquals("Native and Material text must use the same readable ink", BeaconTagInk,
                layouts.last().layoutInput.style.color)
            val image = rule.onRoot().captureToImage().asAndroidBitmap()
            val file = java.io.File(rule.activity.getExternalFilesDir("ui-qa"), "BRIGHT_TAGS_${style}_${mode}.png")
            file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            for (command in listOf("mkdir -p /data/local/tmp/beacon-ui-qa", "cp ${file.absolutePath} /data/local/tmp/beacon-ui-qa/${file.name}"))
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    @Test fun materialDialogsKeepGeometryAcrossStages() = transitions(BeaconUiStyle.MATERIAL3)
    @Test fun miuixDialogsKeepGeometryAcrossStages() = transitions(BeaconUiStyle.MIUIX)
    @Test fun materialReminderAndSelectionKeepGeometry() = metadata(BeaconUiStyle.MATERIAL3)
    @Test fun miuixReminderAndSelectionKeepGeometry() = metadata(BeaconUiStyle.MIUIX)
    @Test fun materialFastRefreshOnlyAnimatesItsOwner() = fastRefresh(BeaconUiStyle.MATERIAL3)
    @Test fun miuixFastRefreshOnlyAnimatesItsOwner() = fastRefresh(BeaconUiStyle.MIUIX)
    @Test fun materialTagsStayBrightInBothThemes() = palette(BeaconUiStyle.MATERIAL3)
    @Test fun miuixTagsStayBrightInBothThemes() = palette(BeaconUiStyle.MIUIX)
}
