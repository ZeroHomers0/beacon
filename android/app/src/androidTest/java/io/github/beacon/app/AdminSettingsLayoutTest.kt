package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.AdminListCard
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AdminSettingsLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun verify(style: BeaconUiStyle) {
        val detail = "device-" + "1234567890abcdef".repeat(8)
        for (mode in listOf(BeaconThemeMode.LIGHT, BeaconThemeMode.DARK)) for (scale in listOf(1f, 2f)) {
            val failed = mutableStateOf(false)
            rule.runOnUiThread { rule.activity.setContent {
                BeaconTheme(style, mode) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                Box(Modifier.testTag("overview")) {
                                    SettingsOverviewCard("服务器概况", if (failed.value) "需要检查" else "服务器正常",
                                        "very-long-server-name.example.com", !failed.value, error = failed.value) {
                                        SettingsOverviewField("服务器版本", "1.3.0-workers-long-version")
                                        SettingsOverviewField("版本兼容性", if (failed.value) "暂时无法检查，请刷新重试" else "兼容")
                                    }
                                }
                                Spacer(Modifier.height(20.dp))
                                SettingsSection("运行统计") {
                                    SettingsMetricGrid(listOf("设备" to "123", "消息" to "1,234,567", "待接收" to "0"), columns = 3)
                                }
                                Spacer(Modifier.height(20.dp))
                                AdminListCard("名称很长的 Xiaomi 17 Pro Max 测试设备", detail, "正常", true, emptyList())
                            }
                        }
                    }
                }
            } }
            val before = rule.onNodeWithTag("overview").getUnclippedBoundsInRoot()
            rule.runOnIdle { failed.value = true }
            assertEquals(before, rule.onNodeWithTag("overview").getUnclippedBoundsInRoot())
            rule.runOnIdle { failed.value = false }
            val name = "ADMIN_${style}_${mode}_${scale}"
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            val file = java.io.File(rule.activity.getExternalFilesDir("ui-qa"), "$name.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            for (command in listOf("mkdir -p /data/local/tmp/beacon-ui-qa",
                "cp ${file.absolutePath} /data/local/tmp/beacon-ui-qa/${file.name}")) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
            }
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_more_actions)).performScrollTo().performClick()
            rule.onNodeWithText(rule.activity.getString(R.string.settings_view_details)).performClick()
            rule.onAllNodesWithText(detail).onLast().assertIsDisplayed()
            rule.onNodeWithText(rule.activity.getString(R.string.action_close)).performClick()
        }
    }
    @Test fun materialManagementLayoutAndDetails() = verify(BeaconUiStyle.MATERIAL3)
    @Test fun miuixManagementLayoutAndDetails() = verify(BeaconUiStyle.MIUIX)
}
