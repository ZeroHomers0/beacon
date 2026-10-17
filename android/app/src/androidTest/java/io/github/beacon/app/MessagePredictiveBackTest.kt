package io.github.beacon.app

import androidx.activity.BackEventCompat
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.ui.BeaconViewModel
import io.github.beacon.app.ui.components.SettingsScreen
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercise the real activity back dispatcher, including a cancelled gesture. */
class MessagePredictiveBackTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun navigation(style: BeaconUiStyle, shortWindow: Boolean = false, normalBack: Boolean = false) {
        val key = MessageKey("predictive-back", UUID.randomUUID().toString())
        val title = "手势详情 ${key.id}"
        val message = IncomingMessage(
            key.server, key.id, title, "预测性返回正文",
            receivedAtMs = System.currentTimeMillis(),
            applicationId = "a".repeat(64), applicationName = "回归应用", group = "回归分组",
        )
        val model = ViewModelProvider(rule.activity)[BeaconViewModel::class.java]
        val originalStyle = model.uiState.uiStyle
        RuntimeMessageStore(rule.activity).use { store -> store.save(message) }
        try {
            rule.waitUntil(15_000) {
                model.state.value.runtimeConnected && !model.state.value.messageBusy
            }
            rule.runOnUiThread {
                model.uiState.uiStyle = style
                model.uiState.page = 0
                model.uiState.settingsScreen = SettingsScreen.HOME
                model.uiState.selectionMode = false
                model.uiState.filtersOpen = false
                if (shortWindow) rule.activity.showNavigationTestWindow(model)
                model.readMessages()
            }
            rule.waitUntil(15_000) {
                !model.state.value.messageBusy && model.state.value.messages.any { it.id == key.id }
            }
            rule.runOnUiThread { model.openMessage(key) }
            rule.waitUntil(15_000) {
                model.state.value.selectedMessage?.id == key.id && !model.state.value.messageBusy
            }
            rule.waitForIdle()
            rule.onNodeWithText("回归应用").assertIsDisplayed()
            rule.onNodeWithText("回归分组").assertIsDisplayed()
            val initial = rule.onNodeWithText(title).getUnclippedBoundsInRoot()
            val detailTitle = rule.activity.getString(R.string.ui_message_detail_title)
            val parentTitle = rule.activity.getString(R.string.ui_nav_messages_title)
            val initialHeader = rule.onNodeWithText(detailTitle, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val dispatcher = rule.activity.onBackPressedDispatcher
            if (normalBack) {
                rule.mainClock.autoAdvance = false
                rule.onNodeWithContentDescription(rule.activity.getString(R.string.ui_back_to_messages_action)).performClick()
                rule.mainClock.advanceTimeByFrame()
                rule.mainClock.advanceTimeByFrame()
                rule.mainClock.advanceTimeBy(150)
                val header = rule.onNodeWithText(detailTitle, useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertTrue("Normal back moves the outgoing header", header.left.value > initialHeader.left.value + 10f)
                rule.onAllNodesWithText(parentTitle, useUnmergedTree = true).onFirst().assertExists()
                rule.mainClock.advanceTimeBy(700)
                rule.onNodeWithText(detailTitle, useUnmergedTree = true).assertDoesNotExist()
                rule.mainClock.autoAdvance = true
                rule.onNodeWithText(title).assertIsDisplayed()
                return
            }
            rule.runOnUiThread {
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            }
            rule.waitForIdle()
            rule.runOnUiThread {
                dispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 200f, .4f, BackEventCompat.EDGE_LEFT))
            }
            rule.waitForIdle()
            val seeking = rule.onAllNodesWithText(title).fetchSemanticsNodes()
            val density = rule.activity.resources.displayMetrics.density
            val header = rule.onNodeWithText(detailTitle, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val bitmap = rule.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
            val output = java.io.File(rule.activity.getExternalFilesDir(null), "nav-mid-$style-$shortWindow.png")
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val copy = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/nav-mid-$style-$shortWindow.png")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
            android.util.Log.i("NavigationQA", "header=$header initialHeader=$initialHeader body=$initial seeking=${seeking.map { it.boundsInRoot }}")
            assertTrue("The header follows the back gesture", header.left.value > initialHeader.left.value + 10f)
            val bodyShift = seeking.maxOf { it.boundsInRoot.left } / density - initial.left.value
            assertEquals("Header and detail move together", bodyShift, header.left.value - initialHeader.left.value, 2f)
            rule.onAllNodesWithText(parentTitle, useUnmergedTree = true).onFirst().assertExists()
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.ui_nav_settings_title)).assertExists()
            assertTrue("Detail follows the back gesture", seeking.any { it.boundsInRoot.left > initial.left.value * density + 10f })
            rule.assertSmoothBackCancellation(
                initialHeader.left.value, header.left.value,
                cancel = { rule.runOnUiThread { dispatcher.dispatchOnBackCancelled() } },
                position = { rule.onNodeWithText(detailTitle, useUnmergedTree = true).getUnclippedBoundsInRoot().left.value },
            )
            rule.waitForIdle()
            assertEquals("Cancelling retains the message", key.id, model.state.value.selectedMessage?.id)
            val restored = rule.onNodeWithText(title).getUnclippedBoundsInRoot()
            assertEquals(initial.left.value, restored.left.value, 1f)
            rule.runOnUiThread {
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            }
            rule.waitForIdle()
            rule.runOnUiThread {
                dispatcher.dispatchOnBackProgressed(BackEventCompat(240f, 200f, .7f, BackEventCompat.EDGE_LEFT))
            }
            rule.waitForIdle()
            rule.runOnUiThread { dispatcher.onBackPressed() }
            rule.waitUntil(15_000) { model.state.value.selectedMessage == null }
            rule.waitForIdle()
            rule.onNodeWithText(title).assertIsDisplayed()
            rule.onNodeWithText("查看消息信息").assertDoesNotExist()
        } finally {
            rule.mainClock.autoAdvance = true
            rule.runOnUiThread {
                model.uiState.closeDetail(model)
                model.uiState.uiStyle = originalStyle
            }
            RuntimeMessageStore(rule.activity).use { store -> store.remove(key.server, key.id) }
        }
    }

    @Test fun detailGestureCancelAndCommitMiuix() = navigation(BeaconUiStyle.MIUIX)

    @Test fun detailGestureCancelAndCommitMaterial() = navigation(BeaconUiStyle.MATERIAL3)
    @Test fun detailShortWindowGestureMiuix() = navigation(BeaconUiStyle.MIUIX, shortWindow = true)
    @Test fun detailShortWindowGestureMaterial() = navigation(BeaconUiStyle.MATERIAL3, shortWindow = true)
    @Test fun detailNormalBackHeaderMiuix() = navigation(BeaconUiStyle.MIUIX, normalBack = true)
    @Test fun detailNormalBackHeaderMaterial() = navigation(BeaconUiStyle.MATERIAL3, normalBack = true)
}

