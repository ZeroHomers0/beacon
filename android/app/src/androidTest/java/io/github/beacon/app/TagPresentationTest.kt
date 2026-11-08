package io.github.beacon.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.AdminListCard
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TagPresentationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun verify(style: BeaconUiStyle) {
        for (mode in listOf(BeaconThemeMode.LIGHT, BeaconThemeMode.DARK)) {
            var filters = 0
            rule.runOnUiThread {
                rule.activity.enableEdgeToEdge()
                rule.activity.setContent {
                    BeaconTheme(style, mode) {
                        BeaconScaffold(topBar = { BeaconTopBar("标签与选择项", true, rememberBeaconBarBehavior(), {}, {}) }, bottomBar = {}) { insets ->
                            Column(Modifier.fillMaxSize().padding(insets).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                MessageItem(IncomingMessage("synthetic", "tags", "每日构建已完成", "产物上传完成，可查看报告。",
                                    applicationId="ci", applicationName="CI 构建", group="发布通知"),
                                    false,false,{}, {}, {},false,{}, {}, {}, onFilterGroup = { filters++ })
                                AdminListCard("主设备", "Android 手机", "已绑定", true, emptyList())
                                AdminListCard("旧设备", "保留历史记录", "已解绑", true, emptyList(), revoked=true)
                                SettingsGroup("状态标记") {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        MessageFilterTag("正常", tone=BeaconTagTone.SUCCESS)
                                        MessageFilterTag("数据较旧", tone=BeaconTagTone.WARNING)
                                        MessageFilterTag("连接失败", tone=BeaconTagTone.DANGER)
                                    }
                                }
                                SettingsGroup("选择项") {
                                    var workdays by remember { mutableStateOf(false) }
                                    Row(Modifier.padding(16.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        BeaconFilterChip(!workdays, { workdays=false }, { BeaconText("每天") })
                                        BeaconFilterChip(workdays, { workdays=true }, { BeaconText("工作日") })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            rule.onNodeWithText("发布通知").performClick()
            rule.runOnIdle { assertEquals(1, filters) }
            rule.onNodeWithText("工作日").performClick().assertIsSelected()
            rule.onNodeWithText("每天").assertIsNotSelected()
            rule.mainClock.advanceTimeBy(600)
            rule.waitForIdle()
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            val name = "tags-$style-$mode.png"
            val output = java.io.File(rule.activity.getExternalFilesDir(null), name)
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            val copy = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("cp ${output.absolutePath} /data/local/tmp/$name")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
        }
    }

    @Test fun materialTagsAndChoices() = verify(BeaconUiStyle.MATERIAL3)
    @Test fun miuixTagsAndChoices() = verify(BeaconUiStyle.MIUIX)
}
