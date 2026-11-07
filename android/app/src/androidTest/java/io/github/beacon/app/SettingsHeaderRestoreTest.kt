package io.github.beacon.app

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.BeaconViewModel
import io.github.beacon.app.ui.components.SettingsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsHeaderRestoreTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun restore(style: BeaconUiStyle) {
        val model = ViewModelProvider(rule.activity)[BeaconViewModel::class.java]
        val ui = model.uiState
        val originalStyle = ui.uiStyle
        rule.waitUntil(15_000) { model.state.value.runtimeConnected && !model.state.value.messageBusy }
        try {
            rule.runOnUiThread {
                ui.closeDetail(model); ui.selectionMode = false; ui.uiStyle = style
                ui.page = 1; ui.navigateSettings(SettingsScreen.HOME)
            }
            rule.mainClock.advanceTimeBy(1000)
            rule.waitForIdle()
            val header = rule.onNodeWithTag("settings-header-1-HOME")
            val expanded = header.getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
            val expandedImage = header.captureToImage()
            capture("header-before-$style")
            val body = rule.onNodeWithTag(if (style == BeaconUiStyle.MIUIX) "settings-body-HOME" else "settings-content-HOME")
            body.assertIsDisplayed().performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(5f, height * .85f),
                    androidx.compose.ui.geometry.Offset(5f, height * .2f), durationMillis = 500)
            }
            rule.waitForIdle()
            val collapsed = header.getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
            val collapsedImage = header.captureToImage()
            capture("header-collapsed-$style")
            assertTrue("The gesture must change the header presentation: $expanded -> $collapsed", imageDifference(expandedImage, collapsedImage) > .001f)
            if (style == BeaconUiStyle.MATERIAL3) assertTrue(collapsed < expanded - 10f)
            rule.runOnUiThread { ui.navigateSettings(SettingsScreen.STORAGE) }
            rule.onNodeWithTag("settings-header-1-STORAGE").assertExists()
            rule.runOnUiThread { ui.backSettings() }
            rule.waitForIdle()
            assertEquals("Returning must preserve the collapsed title", collapsed,
                rule.onNodeWithTag("settings-header-1-HOME").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }, 1f)
            val restored = rule.onNodeWithTag("settings-header-1-HOME").captureToImage()
            assertTrue("Returning must restore the same title, not just the same header bounds",
                imageDifference(collapsedImage, restored) < .005f)
        } finally {
            rule.runOnUiThread { ui.navigateSettings(SettingsScreen.HOME); ui.page = 0; ui.uiStyle = originalStyle }
        }
    }
    private fun capture(name: String) {
        val file = java.io.File(rule.activity.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/$name.png")).use { it.readBytes() }
    }
    private fun imageDifference(first: ImageBitmap, second: ImageBitmap): Float {
        if (first.width != second.width || first.height != second.height) return 1f
        val a = first.toPixelMap(); val b = second.toPixelMap()
        var total = 0f; var count = 0
        for (y in 0 until a.height step 4) for (x in 0 until a.width step 4) {
            val p = a[x,y]; val q = b[x,y]
            total += kotlin.math.abs(p.red-q.red) + kotlin.math.abs(p.green-q.green) + kotlin.math.abs(p.blue-q.blue)
            count += 3
        }
        return total / count
    }
    @Test fun miuixRestoresCollapsedSettingsHeader() = restore(BeaconUiStyle.MIUIX)
    @Test fun materialRestoresCollapsedSettingsHeader() = restore(BeaconUiStyle.MATERIAL3)
}
