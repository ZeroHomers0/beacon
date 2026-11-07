package io.github.beacon.app

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.BeaconViewModel
import io.github.beacon.app.ui.components.SettingsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ToolbarGroupingTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        val model = ViewModelProvider(rule.activity)[BeaconViewModel::class.java]
        val ui = model.uiState
        val oldStyle = ui.uiStyle
        rule.runOnUiThread { model.start(rule.activity) }
        rule.waitUntil(20_000) { model.state.value.runtimeConnected && !model.state.value.messageBusy }
        try {
            rule.runOnUiThread {
                ui.uiStyle = style
                ui.closeDetail(model)
                ui.selectionMode = false
                ui.page = 0
            }
            rule.onNodeWithText(rule.activity.getString(R.string.ui_nav_messages_title)).assertDoesNotExist()
            val labels = listOf(R.string.ui_mark_all_read_action, R.string.action_sync_now,
                R.string.ui_filter_messages_title, R.string.ui_nav_settings_title)
            val bounds = labels.map {
                val description = rule.activity.getString(it)
                rule.onNode(hasClickAction() and (hasContentDescription(description) or
                    hasAnyDescendant(hasContentDescription(description)))).assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
            }
            capture("home-$style")
            assertTrue("All four home actions share a single row: $bounds", bounds.all {
                kotlin.math.abs((it.top.value + it.bottom.value) / 2 - (bounds[0].top.value + bounds[0].bottom.value) / 2) < 1f
            })
            assertTrue("Actions keep their existing touch width", bounds.all { (it.right - it.left).value >= 44f })
            rule.runOnUiThread { ui.selectionMode = true; ui.selectedKeys = emptyList() }
            rule.onNodeWithText(rule.activity.getString(R.string.msg_selected_count_label, 0)).assertIsDisplayed()
            rule.runOnUiThread { ui.selectionMode = false; ui.page = 1; ui.navigateSettings(SettingsScreen.CONNECTION) }
            val check = rule.activity.getString(R.string.server_check_action)
            rule.onAllNodesWithContentDescription(check).assertCountEquals(1)
            assertTrue("Connection refresh is in the top bar", rule.onNodeWithContentDescription(check)
                .getUnclippedBoundsInRoot().top.value < 100f)
            capture("connection-$style")
            rule.runOnUiThread { ui.navigateSettings(SettingsScreen.SERVER_ADMIN) }
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_refresh)).assertIsDisplayed()
            capture("admin-$style")
        } finally {
            rule.runOnUiThread { ui.selectionMode = false; ui.uiStyle = oldStyle; ui.navigateSettings(SettingsScreen.HOME); ui.page = 0 }
        }
    }

    private fun capture(name: String) {
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        val file = java.io.File(rule.activity.getExternalFilesDir("ui-qa"), "$name.png")
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("mkdir -p /data/local/tmp/beacon-toolbar-qa",
                "cp ${file.absolutePath} /data/local/tmp/beacon-toolbar-qa/${file.name}"))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }

    @Test fun miuixHomeAndManagementToolbars() = verify(BeaconUiStyle.MIUIX)
    @Test fun materialHomeAndManagementToolbars() = verify(BeaconUiStyle.MATERIAL3)
}
