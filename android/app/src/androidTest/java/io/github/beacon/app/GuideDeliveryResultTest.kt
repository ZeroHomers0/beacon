package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.FirstUseGuidePanel
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Rule
import org.junit.Test

class GuideDeliveryResultTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        val state = mutableStateOf(RuntimeState(runtimeConnected = true, configuredEndpoint = "https://example.invalid"))
        var failure: ((String) -> Unit)? = null
        var success: ((String) -> Unit)? = null
        val actions = object : RuntimeActions by repository {
            override fun testDelivery(onFailure: (String) -> Unit, onComplete: (String) -> Unit) {
                failure = onFailure
                success = onComplete
            }
        }
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            FirstUseGuidePanel(state.value, true, actions, {})
                        }
                    }
                }
            }
            fun send() {
                rule.onAllNodesWithText("发送测试消息").onFirst().performScrollTo().performClick()
                rule.onAllNodesWithText("发送测试消息").onLast().performClick()
            }
            rule.onNodeWithText("完成向导").performScrollTo().assertIsNotEnabled()
            send()
            rule.runOnIdle { failure!!("测试未确认") }
            rule.onNodeWithText("完成向导").performScrollTo().assertIsNotEnabled()
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            val file = java.io.File(rule.activity.getExternalFilesDir(null), "status-alignment-$style.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val descriptor = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("cp ${file.absolutePath} /data/local/tmp/${file.name}")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            send()
            rule.runOnIdle { success!!("测试已发送") }
            rule.onNodeWithText("完成向导").performScrollTo().assertIsEnabled()
            rule.runOnIdle { state.value = state.value.copy(configuredEndpoint = "https://another.invalid") }
            rule.onNodeWithText("完成向导").performScrollTo().assertIsNotEnabled()
            rule.runOnIdle { success!!("旧请求迟到的结果") }
            rule.onNodeWithText("完成向导").performScrollTo().assertIsNotEnabled()
        } finally {
            rule.runOnUiThread { repository.close() }
        }
    }
    @Test fun materialFailureDoesNotCompleteGuide() = verify(BeaconUiStyle.MATERIAL3)
    @Test fun miuixFailureDoesNotCompleteGuide() = verify(BeaconUiStyle.MIUIX)
}
