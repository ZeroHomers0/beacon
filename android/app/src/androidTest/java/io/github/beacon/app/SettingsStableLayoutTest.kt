package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsStableLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        val refreshed = mutableStateOf(false)
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                        Column(Modifier.width(280.dp)) {
                            Box(Modifier.testTag("status")) {
                                SettingsStatusSummary(
                                    title = if (refreshed.value) "连接失败" else "连接正常",
                                    details = if (refreshed.value) "服务器暂时无法连接，请检查网络后重试。" else "",
                                    interactive = false, reserveDetails = true,
                                    caption = "上次检查 · 2026-10-24 12:34:56",
                                )
                            }
                            Box(Modifier.testTag("metrics")) {
                                SettingsMetricGrid(listOf("设备" to "1", "消息" to
                                    if (refreshed.value) "123456789" else "—", "待接收" to "0"), columns = 3)
                            }
                            BeaconButton({}, Modifier.testTag("below")) { BeaconText("操作") }
                        }
                    }
                }
            }
        }
        val tags = listOf("status", "metrics", "below")
        val before = tags.map { rule.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        rule.runOnIdle { refreshed.value = true }
        rule.waitForIdle()
        tags.forEachIndexed { index, tag ->
            assertEquals(before[index], rule.onNodeWithTag(tag).getUnclippedBoundsInRoot())
        }
    }

    private fun verifyOverview(style: BeaconUiStyle, scale: Float) {
        val failed = mutableStateOf(false)
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        Column(Modifier.width(280.dp).testTag("overview")) {
                            SettingsOverviewCard(
                                title = "连接状态",
                                status = if (failed.value) "连接失败" else "服务器已连接",
                                subtitle = "very-long-server-name.example.com",
                                healthy = !failed.value, error = failed.value,
                            ) {
                                SettingsOverviewField("设备名称", "Xiaomi 17")
                                SettingsOverviewField("上次检查", "23:59:59")
                                SettingsOverviewField("检测结果", if (failed.value)
                                    "服务器暂时无法连接\n请检查网络后重试" else "连接正常")
                                SettingsPrimaryButton("修改连接", Modifier.fillMaxWidth()) {}
                            }
                        }
                    }
                }
            }
        }
        val before = rule.onNodeWithTag("overview").getUnclippedBoundsInRoot()
        val action = rule.onNodeWithText("修改连接").getUnclippedBoundsInRoot()
        rule.runOnIdle { failed.value = true }
        rule.waitForIdle()
        assertEquals(before, rule.onNodeWithTag("overview").getUnclippedBoundsInRoot())
        assertEquals(action, rule.onNodeWithText("修改连接").getUnclippedBoundsInRoot())
        rule.onNodeWithText("服务器暂时无法连接 · 请检查网络后重试").assertExists()
    }

    @Test fun materialOverviewKeepsBounds() = verifyOverview(BeaconUiStyle.MATERIAL3, 1f)
    @Test fun miuixOverviewKeepsBounds() = verifyOverview(BeaconUiStyle.MIUIX, 1f)
    @Test fun materialLargeOverviewKeepsBounds() = verifyOverview(BeaconUiStyle.MATERIAL3, 2f)
    @Test fun miuixLargeOverviewKeepsBounds() = verifyOverview(BeaconUiStyle.MIUIX, 2f)

    @Test fun materialRefreshKeepsBounds() = verify(BeaconUiStyle.MATERIAL3)
    @Test fun miuixRefreshKeepsBounds() = verify(BeaconUiStyle.MIUIX)
    private fun verifyTimeActions(style: BeaconUiStyle) {
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    BeaconTimePreference("08:30", {}, "选择时间")
                }
            }
        }
        rule.onNodeWithText("08:30", substring = true).performClick()
        rule.waitForIdle()
        val cancel = rule.onNodeWithText(rule.activity.getString(R.string.action_cancel))
            .getUnclippedBoundsInRoot()
        val confirm = rule.onNodeWithText(rule.activity.getString(R.string.settings_confirm))
            .getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue(kotlin.math.abs(cancel.top.value - confirm.top.value) < 2f)
        org.junit.Assert.assertTrue(kotlin.math.abs((cancel.right - cancel.left).value - (confirm.right - confirm.left).value) < 2f)
        org.junit.Assert.assertTrue(cancel.right <= confirm.left)
        rule.onNodeWithText(rule.activity.getString(R.string.action_cancel)).performClick()
    }
    @Test fun materialTimeActionsShareOneRow() = verifyTimeActions(BeaconUiStyle.MATERIAL3)
    @Test fun miuixTimeActionsShareOneRow() = verifyTimeActions(BeaconUiStyle.MIUIX)

}
