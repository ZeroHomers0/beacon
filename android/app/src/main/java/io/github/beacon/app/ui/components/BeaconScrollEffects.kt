package io.github.beacon.app.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity

private data class ScrollFrame(val index: Int, val offset: Int, val scrolling: Boolean, val sizes: List<Pair<Int, Int>>)

internal val LocalBeaconScrollProgress = staticCompositionLocalOf<() -> Float> { { 0f } }
internal val LocalBeaconToolbarGlassAlpha = staticCompositionLocalOf<() -> Float> { { 1f } }

/** Read in layout/draw: scrolling does not start a timer or change the list's saved position. */
@Composable
internal fun rememberBeaconScrollProgress(scroll: LazyListState): () -> Float {
    val distance = with(LocalDensity.current) { BeaconUiMetrics.SearchCollapseDistance.toPx() }
    val travelled = remember(scroll) { mutableFloatStateOf(0f) }
    LaunchedEffect(scroll, distance) {
        val heights = mutableMapOf<Int, Int>()
        var previousIndex = scroll.firstVisibleItemIndex
        var previousOffset = scroll.firstVisibleItemScrollOffset
        travelled.floatValue = if (previousIndex == 0) previousOffset.toFloat() else distance
        var wasScrolling = false
        snapshotFlow {
            ScrollFrame(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, scroll.isScrollInProgress,
                scroll.layoutInfo.visibleItemsInfo.map { it.index to it.size + scroll.layoutInfo.mainAxisItemSpacing })
        }.collect { (index, offset, scrolling, sizes) ->
            sizes.forEach { (item, height) -> heights[item] = height }
            var delta = (offset - previousOffset).toFloat()
            if (index > previousIndex) {
                for (item in previousIndex until index) delta += heights[item] ?: distance.toInt()
            } else {
                for (item in index until previousIndex) delta -= heights[item] ?: distance.toInt()
            }
            // Dataset replacement may relocate the anchor without any scroll gesture.
            // Keep the search presentation stable while filters replace or empty the list.
            if (scrolling || wasScrolling) {
                travelled.floatValue = if (index == 0) offset.toFloat()
                    else (travelled.floatValue + delta).coerceAtLeast(0f)
            }
            wasScrolling = scrolling
            previousIndex = index
            previousOffset = offset
        }
    }
    return remember(scroll, distance) {
        { (travelled.floatValue / distance).coerceIn(0f, 1f) }
    }
}

/** Home search collapse is gesture state, never a derived result-list position. */
class BeaconSearchScrollState internal constructor(private val scroll: LazyListState, private val distance: Float) :
    androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    private var collapsed by mutableFloatStateOf(if (scroll.firstVisibleItemIndex > 0) distance
        else scroll.firstVisibleItemScrollOffset.toFloat().coerceAtMost(distance))
    val progress: () -> Float = { (collapsed / distance).coerceIn(0f, 1f) }
    fun expand() { collapsed = 0f }
    override fun onPreScroll(available: androidx.compose.ui.geometry.Offset,
        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
        if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput &&
            available.y > 0f && !scroll.canScrollBackward && collapsed > 0f) {
            val used = minOf(available.y, collapsed)
            collapsed -= used
            return androidx.compose.ui.geometry.Offset(0f, used)
        }
        return androidx.compose.ui.geometry.Offset.Zero
    }
    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
        if (consumed.y < 0f) collapsed = (collapsed - consumed.y).coerceAtMost(distance)
        return androidx.compose.ui.geometry.Offset.Zero
    }
}

@Composable
internal fun rememberBeaconSearchScrollState(scroll: LazyListState): BeaconSearchScrollState {
    val distance = with(LocalDensity.current) { BeaconUiMetrics.SearchCollapseDistance.toPx() }
    return remember(scroll, distance) { BeaconSearchScrollState(scroll, distance) }
}
