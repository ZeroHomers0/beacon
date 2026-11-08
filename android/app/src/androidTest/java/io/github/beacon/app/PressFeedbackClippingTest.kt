package io.github.beacon.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.feature.settings.AdminListCard
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class PressFeedbackClippingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun verify(style: BeaconUiStyle) {
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    Column(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                        .padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        AdminListCard("测试设备", "设备标识", "已绑定", true, emptyList(),
                            modifier = Modifier.testTag("admin-card"), onDetails = {})
                        Box(Modifier.testTag("device-picker")) {
                            BeaconDropdownPreference("选择设备", listOf("工作手机", "备用手机"), 0) {}
                        }
                        Box(Modifier.testTag("switch-row")) {
                            BeaconPreferenceToggle("测试开关", "描述", true, onCheckedChange = {})
                        }
                    }
                }
            }
        }
        for (tag in listOf("admin-card", "device-picker", "switch-row")) {
            val node = rule.onNodeWithTag(tag)
            val before = node.captureToImage().asAndroidBitmap()
            node.performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(100)
            android.os.SystemClock.sleep(350)
            val held = node.captureToImage().asAndroidBitmap()
            // All four outer corners must remain outside the row/card press overlay.
            for (x in listOf(1, before.width - 2)) for (y in listOf(1, before.height - 2)) {
                assertEquals("$style $tag corner ($x,$y)", before.getPixel(x,y), held.getPixel(x,y))
            }
            if (style == BeaconUiStyle.MIUIX) {
                for ((x,y) in listOf(2 to before.height/2, before.width-3 to before.height/2,
                    before.width/2 to 2, before.width/2 to before.height-3)) {
                    assertNotEquals("$style $tag press must cover row edge ($x,$y)", before.getPixel(x,y), held.getPixel(x,y))
                }
            }
            node.performTouchInput { cancel() }
            rule.mainClock.advanceTimeBy(500)
            android.os.SystemClock.sleep(250)
        }
    }
    @Test fun materialFeedbackStaysInsideCorners() = verify(BeaconUiStyle.MATERIAL3)
    @Test fun miuixFeedbackStaysInsideCorners() = verify(BeaconUiStyle.MIUIX)
}
