package io.github.beacon.app

import android.content.pm.PackageManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.feature.settings.BackgroundPermissionPanel
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundPermissionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        BackgroundPermissionPanel()
                    }
                }
            }
        }
    }

    private fun rendered() = verify(BeaconUiStyle.MATERIAL3)

    private fun items() {
        for (title in listOf("自启动", "省电策略无限制")) {
            rule.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
        rule.onAllNodesWithText("需手动确认").assertCountEquals(1)
        rule.onAllNodesWithText(rule.activity.getString(R.string.background_open_app_settings))
            .assertCountEquals(1)
        rule.onNodeWithText(rule.activity.getString(R.string.background_open_battery_settings))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("后台卡片锁定").assertDoesNotExist()
        rule.onNodeWithText("查看操作步骤").assertDoesNotExist()
        rule.onNodeWithText("按接收模式运行").assertDoesNotExist()
    }

    private fun refresh() {
        rule.onNodeWithText(rule.activity.getString(R.string.action_refresh_status)).assertDoesNotExist()
        rule.onNodeWithText("系统后台限制").assertExists()
    }

    @Test
    fun materialPermissionsAndRefresh() {
        rendered()
        items()
        refresh()
    }

    @Test
    fun miuixPermissionsAndRefresh() {
        verify(BeaconUiStyle.MIUIX)
        items()
        refresh()
    }

    @Test
    fun noExportedReceptionProviders() {
        rendered()
        val info =
            rule.activity.packageManager.getPackageInfo(
                rule.activity.packageName,
                PackageManager.GET_PROVIDERS,
            )
        assertTrue(
            "App must not expose reception status through public providers",
            info.providers.orEmpty().none { it.exported },
        )
    }
}
