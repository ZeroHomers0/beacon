package io.github.beacon.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.components.SettingsMetricGrid
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompactMetricsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun verify(style: BeaconUiStyle, scale: Float) {
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            SettingsMetricGrid(listOf("数据库" to "12 MB", "消息" to "128 条", "未读" to "24 条", "待回传" to "8 条", "待通知" to "6 条", "临时日志" to "4 MB"), columns = 3)
                            Spacer(Modifier.height(16.dp))
                            SettingsMetricGrid(listOf("可清理" to "12 条", "待回传保留" to "8 条", "通知保留" to "6 条"), columns = 3)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        fun bounds(label: String) = rule.onNodeWithText(label).getUnclippedBoundsInRoot()
        fun verifyReadingOrder(labels: List<String>) {
            labels.zipWithNext().forEach { (first, next) ->
                val a = bounds(first)
                val b = bounds(next)
                if (kotlin.math.abs(a.top.value - b.top.value) < 1f) {
                    assertTrue("Adjacent metrics must not overlap", a.right < b.left)
                } else {
                    assertTrue("Wrapped metrics must preserve reading order", b.top > a.bottom)
                }
            }
        }
        verifyReadingOrder(listOf("数据库", "消息", "未读", "待回传", "待通知", "临时日志"))
        verifyReadingOrder(listOf("可清理", "待回传保留", "通知保留"))
        // Large text is allowed to reduce column count; forcing three columns clips labels.
        if (scale == 1f) {
            assertEquals(bounds("数据库").top.value, bounds("未读").top.value, 1f)
        }
        rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            val file = java.io.File(rule.activity.getExternalFilesDir(null), "metrics-${style.name}-$scale.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/${file.name}").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
    }
    @Test fun materialNormal() = verify(BeaconUiStyle.MATERIAL3, 1f)
    @Test fun miuixNormal() = verify(BeaconUiStyle.MIUIX, 1f)
    @Test fun materialLargeFont() = verify(BeaconUiStyle.MATERIAL3, 2f)
    @Test fun miuixLargeFont() = verify(BeaconUiStyle.MIUIX, 2f)
}
