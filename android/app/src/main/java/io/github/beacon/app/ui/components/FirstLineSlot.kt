package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit

/**
 * Holds a control in the vertical space of one line of text, centre-aligned.
 *
 * Trailing controls in a title row have a fixed size — a 32dp icon button, a 48dp touch target —
 * and a title has a line height. Laying them out as one row means whichever is taller sets the row
 * height: centre-align the row and the control drifts to the middle of a wrapped title, top-align
 * it and the control crowds the top of the card. This reports the *line's* height instead and lets
 * the control overflow symmetrically, so its centre sits on the first line's centre whatever the
 * title does, and the row keeps the title's own height.
 *
 * [unbounded] is what allows the overflow: the control is measured with no height limit and the
 * slot then reports the line height, centred. Nothing clips, and the overflow lands inside the
 * card's own inset.
 */
@Composable
internal fun FirstLineSlot(lineHeight: TextUnit, content: @Composable () -> Unit) {
    val line = with(LocalDensity.current) { lineHeight.toDp() }
    Box(Modifier.height(line).wrapContentHeight(Alignment.CenterVertically, unbounded = true)) {
        content()
    }
}
