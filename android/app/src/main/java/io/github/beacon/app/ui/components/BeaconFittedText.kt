package io.github.beacon.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/** Measure before drawing so metadata shrinks without a visible layout jump. */
@Composable
fun BeaconFittedText(text: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium, color: Color = Color.Unspecified,
    inspectable: Boolean = false, inspectTitle: String = "", fullText: String = text,
) {
    var showingFullText by remember(fullText) { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val base = if (style.fontSize.isSp) style.fontSize.value else 14f
    val lineHeight = with(LocalDensity.current) { base.sp.toDp() * 1.4f }
    BoxWithConstraints(modifier.height(lineHeight), contentAlignment = Alignment.CenterStart) {
        val floor = minOf(base, 11f)
        var size = base
        while (size > floor && measurer.measure(text, style.copy(fontSize = size.sp),
                softWrap = false, maxLines = 1).size.width > constraints.maxWidth) {
            size = (size - .5f).coerceAtLeast(floor)
        }
        val canInspect = inspectable && (size < base || fullText != text ||
            measurer.measure(text, style.copy(fontSize = size.sp), softWrap = false, maxLines = 1).size.width > constraints.maxWidth)
        BeaconText(text, modifier = Modifier.fillMaxWidth().then(
            if (canInspect) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { showingFullText = true }
            else Modifier), style = style.copy(fontSize = size.sp), color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (showingFullText) BeaconAlertDialog(
        onDismissRequest = { showingFullText = false },
        title = { BeaconText(inspectTitle.ifBlank { stringResource(R.string.settings_view_details) }) },
        text = { SelectionContainer { BeaconText(fullText) } },
        confirmButton = { BeaconButton({ showingFullText = false }) { BeaconText(stringResource(R.string.action_close)) } },
    )
}
