package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.ReceiveRulesPanel
import io.github.beacon.app.feature.settings.ReceptionFormState
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReceiveRulesCompactTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun verify(style: BeaconUiStyle) {
        val form = ReceptionFormState()
        val busy = mutableStateOf(false)
        rule.runOnUiThread {
            form.reset(PollingConfig(), ReceivePlan(mode = ReceiveMode.RULES, realtimeOnWifi = false, realtimeWhileCharging = false))
            rule.activity.setContent { BeaconTheme(style) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { ReceiveRulesPanel(form, busy.value) }
            } }
        }
        rule.onNodeWithText("接收规则").assertIsDisplayed()
        rule.onNodeWithText("启用时间段").assertDoesNotExist()
        rule.onNodeWithText("24 小时制，例如 22:00–08:00").assertDoesNotExist()
        rule.onNodeWithContentDescription("低电量保护").performClick()
        rule.onNodeWithText("剩余电量不高于").performClick()
        rule.onNodeWithText("30%").performClick()
        rule.runOnIdle { assertTrue(form.plan.lowBatteryEnabled); assertEquals(30, form.plan.lowBatteryPercent); assertTrue(form.edited) }
        rule.onNodeWithContentDescription("每日时间段").performScrollTo().performClick()
        rule.onNodeWithText("时间段内接收方式").performScrollTo().performClick()
        rule.onNodeWithText("省电接收").performClick()
        rule.runOnIdle { assertTrue(form.plan.scheduleEnabled); assertFalse(form.plan.scheduleRealtime); form.scheduleEndText = form.scheduleStartText }
        rule.onNodeWithText("请选择有效的开始与结束时间，且两者不能相同").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertFalse(form.scheduleValid); form.scheduleEndText = "18:00" }
        rule.onNodeWithContentDescription("充电时即时接收").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Wi-Fi 时即时接收").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(form.plan.realtimeWhileCharging); assertTrue(form.plan.realtimeOnWifi) }
        rule.onNodeWithContentDescription("每日时间段").performScrollTo().performClick()
        rule.onNodeWithText("时间段内接收方式").assertDoesNotExist()
        rule.runOnIdle { assertFalse(form.plan.scheduleEnabled) }
        rule.onNodeWithText("接收规则").performScrollTo()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = java.io.File(rule.activity.getExternalFilesDir(null), "compact-rules-$style.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val descriptor = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/${file.name}")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        rule.runOnIdle { busy.value = true }
        rule.onNodeWithContentDescription("低电量保护").assertIsNotEnabled()
        rule.onNodeWithContentDescription("每日时间段").assertIsNotEnabled()
    }
    @Test fun materialCompactRules() = verify(BeaconUiStyle.MATERIAL3)
    @Test fun miuixCompactRules() = verify(BeaconUiStyle.MIUIX)
}
