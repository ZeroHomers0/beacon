package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.roundToInt

/** Read the content pager's position so toolbars follow precisely the same spring motion. */
@Composable
fun BeaconPagerToolbar(pager: PagerState, content: @Composable (Int) -> Unit) {
    Layout(
        modifier = Modifier.fillMaxWidth().clipToBounds(),
        content = { repeat(pager.pageCount) { page -> Box { content(page) } } },
    ) { measurables, constraints ->
        val bars = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val position =
            (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(
                0f,
                (bars.size - 1).toFloat(),
            )
        val left = position.toInt()
        val right = (left + 1).coerceAtMost(bars.lastIndex)
        val fraction = position - left
        val height =
            (bars[left].height * (1 - fraction) + bars[right].height * fraction).roundToInt()
        layout(constraints.maxWidth, constraints.constrainHeight(height)) {
            bars.forEachIndexed { page, bar ->
                bar.placeRelative(((page - position) * constraints.maxWidth).roundToInt(), 0)
            }
        }
    }
}
