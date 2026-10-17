package io.github.beacon.app

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.feature.settings.SettingsBackupPanel
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsBackupPanelTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle, fontScale: Float = 1f) {
        val repository = RuntimeRepository()
        var imports = 0
        val actions =
            object : RuntimeActions by repository {
                override fun settingsBackup(
                    payload: String?,
                    onFailure: () -> Unit,
                    onComplete: (String) -> Unit,
                ) {
                    if (payload != null) imports++
                    onComplete("{}")
                }
            }
        var selections = 0
        val registry =
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    selections++
                    dispatchResult(requestCode, null as android.net.Uri?)
                }
            }
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        CompositionLocalProvider(
                            LocalDensity provides Density(LocalDensity.current.density, fontScale),
                            LocalActivityResultRegistryOwner provides owner,
                        ) {
                            Column { SettingsBackupPanel(actions, false) }
                        }
                    }
                }
            }
            rule.onNodeWithText(rule.activity.getString(R.string.settings_backup_import)).performClick()
            rule.waitForIdle()
            assertEquals(1, selections)
            assertEquals(0, imports)
            rule.onNodeWithText("导入设置备份").assertDoesNotExist()
            rule.onNodeWithText(rule.activity.getString(R.string.settings_backup_export)).performClick()
            rule.waitForIdle()
            assertEquals(2, selections)
            rule.onNodeWithText("设置已导出为 JSON 文件").assertDoesNotExist()
        } finally {
            rule.runOnUiThread { repository.close() }
        }
    }

    @Test fun materialBackupValidationAndCancel() = verify(BeaconUiStyle.MATERIAL3)

    @Test fun miuixBackupValidationAndCancel() = verify(BeaconUiStyle.MIUIX)

    @Test fun materialBackupAtDoubleFontScale() = verify(BeaconUiStyle.MATERIAL3, 2f)

    @Test fun miuixBackupAtDoubleFontScale() = verify(BeaconUiStyle.MIUIX, 2f)
}
