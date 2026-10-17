package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.beacon.app.ui.BeaconApp
import io.github.beacon.app.ui.BeaconViewModel
import io.github.beacon.app.ui.components.NotificationPermissionState

/** Exercise the compact-window title path without changing the emulator's system settings. */
internal fun MainActivity.showNavigationTestWindow(model: BeaconViewModel) {
    setContent {
        val actual = LocalWindowInfo.current
        val density = LocalDensity.current
        val shortWindow = remember(actual, density) {
            object : WindowInfo by actual {
                override val containerSize: IntSize
                    get() = IntSize(actual.containerSize.width, with(density) { 500.dp.roundToPx() })
            }
        }
        Box(Modifier.fillMaxWidth().height(500.dp)) {
            CompositionLocalProvider(LocalWindowInfo provides shortWindow) {
                BeaconApp(model, NotificationPermissionState.ALLOWED, null, {}, {})
            }
        }
    }
}
