package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconTimePreference
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Rule
import org.junit.Test

class TimePreferenceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun select(style: BeaconUiStyle) {
        rule.runOnUiThread {
            rule.activity.setContent {
                var first by remember { mutableStateOf("22:00") }
                var last by remember { mutableStateOf("08:00") }
                BeaconTheme(style) {
                    Column(Modifier.width(320.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BeaconTimePreference(first, { first = it }, "开始时间", Modifier.weight(1f))
                            BeaconTimePreference(last, { last = it }, "结束时间", Modifier.weight(1f))
                        }
                        BeaconText("已选 $first–$last")
                    }
                }
            }
        }
        rule.onNodeWithContentDescription("时 22").assertIsDisplayed()
        rule.onNodeWithContentDescription("时 08").assertIsDisplayed()
        rule.onNodeWithTag("开始时间-hour-wheel").performScrollToIndex(7)
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("已选 07:00–08:00").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("开始时间-minute-wheel").performScrollToIndex(35)
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("已选 07:35–08:00").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasContentDescription("时 07") and isSelected()).assertIsDisplayed()
        rule.onNodeWithContentDescription("分 35").assertIsDisplayed()
        rule.onNodeWithText("已选 07:35–08:00").assertIsDisplayed()
        val bitmap = rule.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        val output = java.io.File(rule.activity.getExternalFilesDir(null), "time-inline-$style.png")
        output.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val copy = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/time-inline-$style.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }

    }

    @Test fun materialTimePicker() = select(BeaconUiStyle.MATERIAL3)

    @Test fun miuixTimePicker() = select(BeaconUiStyle.MIUIX)
}
