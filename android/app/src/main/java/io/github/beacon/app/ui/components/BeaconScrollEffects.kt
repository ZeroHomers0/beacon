package io.github.beacon.app.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity

internal val LocalBeaconScrollProgress = staticCompositionLocalOf<() -> Float> { { 0f } }
internal val LocalBeaconToolbarGlassAlpha = staticCompositionLocalOf<() -> Float> { { 1f } }

/** Read in layout/draw: scrolling does not start a timer or change the list's saved position. */
@Composable
internal fun rememberBeaconScrollProgress(scroll: LazyListState): () -> Float {
    val distance = with(LocalDensity.current) { BeaconUiMetrics.SearchCollapseDistance.toPx() }
    return remember(scroll, distance) {
        { if (scroll.firstVisibleItemIndex > 0) 1f
          else (scroll.firstVisibleItemScrollOffset / distance).coerceIn(0f, 1f) }
    }
}
