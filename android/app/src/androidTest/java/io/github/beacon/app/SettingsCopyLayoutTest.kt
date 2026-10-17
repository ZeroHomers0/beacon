package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.FirstUseGuidePanel
import io.github.beacon.app.feature.settings.StorageSettingsPanel
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsCopyLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        val bitmap = rule.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        val file = java.io.File(rule.activity.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val descriptor = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/${file.name}")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        var clears = 0
        val actions = object : RuntimeActions by repository {
            override fun eventLog(onFailure: () -> Unit, onComplete: (List<RuntimeEvent>) -> Unit) {
                onComplete((1..100).map { RuntimeEvent(1792022400000L + it, RuntimeEventKind.SERVICE_STARTED) })
            }
            override fun clearEventLog(onFailure: () -> Unit, onComplete: () -> Unit) { clears++; onComplete() }
            override fun cleanupSettings(value: CleanupSettings?, onFailure: () -> Unit, onComplete: (CleanupSettings) -> Unit) {
                onComplete(CleanupSettings(lastResult = "2026年10月15日 08:00:00 · 已清理 8 条本地消息"))
            }
            override fun previewCleanup(policy: CleanupPolicy) {}
        }
        try {
            rule.runOnUiThread { rule.activity.setContent {
                BeaconTheme(style) { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    FirstUseGuidePanel(RuntimeState(runtimeConnected = true), false, actions, {})
                } }
            } }
            val titles = listOf("1. 服务器绑定", "2. 通知权限", "3. 接收模式", "4. 推送验证")
            val bounds = titles.map { rule.onNodeWithText(it).performScrollTo().getUnclippedBoundsInRoot() }
            bounds.forEach { assertEquals(bounds.first().left.value, it.left.value, 1f); assertEquals((bounds.first().bottom - bounds.first().top).value, (it.bottom - it.top).value, 1f) }
            rule.onNodeWithText(titles.first()).performScrollTo()
            capture("copy-guide-$style")
            rule.runOnUiThread { rule.activity.setContent {
                BeaconTheme(style) { Column(Modifier.verticalScroll(rememberScrollState())) {
                    StorageSettingsPanel(RuntimeState(runtimeConnected = true), actions)
                } }
            } }
            rule.onNodeWithText("已清理 8 条").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("2026年10月15日 08:00:00 · 已清理 8 条本地消息").assertDoesNotExist()
        } finally { rule.runOnUiThread { repository.close() } }
    }
    @Test fun materialGuideFooterAndCleanupCopy() = verify(BeaconUiStyle.MATERIAL3)
    @Test fun miuixGuideFooterAndCleanupCopy() = verify(BeaconUiStyle.MIUIX)
}

