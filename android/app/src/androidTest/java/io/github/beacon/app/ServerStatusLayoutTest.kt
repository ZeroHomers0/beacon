package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.*
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ServerStatusLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun verify(style: BeaconUiStyle, scale: Float) {
        val repository = RuntimeRepository()
        val initial = RuntimeState(runtimeConnected = true, configuredEndpoint = "https://example.invalid",
            serverReachable = true, serverCheckResult = "检测完成 · 版本 1.2.3 · 协议兼容 · 服务正常", serverCheckedAtMs = 1_000)
        val runtime = mutableStateOf(initial)
        val actions = object : RuntimeActions by repository {
            override fun checkServer() { runtime.value = runtime.value.copy(serverChecking = true, serverCheckResult = "正在检测已保存的服务器…") }
        }
        val label = rule.activity.getString(R.string.server_last_checked)
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        BeaconTheme(style) {
                            val context = LocalContext.current
                            val focus = LocalFocusManager.current
                            val form = remember { ConnectionFormState() }
                            LazyColumn(Modifier.fillMaxWidth()) {
                                connectionSettingsItems(context.resources, runtime.value, actions, form, false,
                                    false, context, focus, "格式错误", {})
                            }
                        }
                    }
                }
            }
            fun rowTop() = rule.onNodeWithText(label).getUnclippedBoundsInRoot().top.value
            val settledTop = rowTop()
            rule.onNodeWithText(rule.activity.getString(R.string.server_check_action)).performScrollTo().performClick()
            // Keep the viewport fixed while comparing document positions.
            rule.waitForIdle()
            assertEquals("The probe must not move the row below its status", settledTop, rowTop(), 0.5f)
            rule.runOnIdle { runtime.value = initial.copy(serverCheckResult = "连接失败", serverReachable = false) }
            assertTrue("A finished result should regain its natural height", rowTop() < settledTop)
            rule.runOnIdle { runtime.value = initial }
            assertEquals(settledTop, rowTop(), 0.5f)
        } finally { rule.runOnUiThread { repository.close() } }
    }
    @Test fun materialStableProbe() = verify(BeaconUiStyle.MATERIAL3, 1f)
    @Test fun miuixStableProbe() = verify(BeaconUiStyle.MIUIX, 1f)
    @Test fun materialLargeFontStableProbe() = verify(BeaconUiStyle.MATERIAL3, 2f)
    @Test fun miuixLargeFontStableProbe() = verify(BeaconUiStyle.MIUIX, 2f)
}
