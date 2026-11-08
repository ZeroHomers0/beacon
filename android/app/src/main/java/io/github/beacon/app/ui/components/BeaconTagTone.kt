package io.github.beacon.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

internal enum class BeaconTagTone { NEUTRAL, SUCCESS, WARNING, DANGER }
internal data class BeaconTagColors(val container: Color, val content: Color, val outline: Color)

/** Radix Colors Slate 3/11, semantic 11/12; see FRONTEND_GUIDELINES for source and scope. */
internal fun tagColors(dark: Boolean, tone: BeaconTagTone = BeaconTagTone.NEUTRAL): BeaconTagColors {
    val background = Color(if (dark) 0xFF272A2D else 0xFFF0F0F3)
    val ink = when (tone) {
        BeaconTagTone.NEUTRAL -> Color(if (dark) 0xFFB0B4BA else 0xFF60646C)
        BeaconTagTone.SUCCESS -> Color(if (dark) 0xFFADF0D4 else 0xFF1D3B31)
        BeaconTagTone.WARNING -> Color(if (dark) 0xFFFFE7B3 else 0xFF4F3422)
        BeaconTagTone.DANGER -> Color(if (dark) 0xFFFF949D else 0xFFCA244D)
    }
    return BeaconTagColors(background, ink, Color(if (dark) 0xFF43484E else 0xFFD9D9E0))
}

/** Choice chips use an explicit selected state, rather than category colours. */
internal fun miuixChoiceColors(dark: Boolean, selected: Boolean): BeaconTagColors =
    if (!selected) tagColors(dark) else BeaconTagColors(
        Color(if (dark) 0xFF182449 else 0xFFEDF2FE),
        Color(if (dark) 0xFF9EB1FF else 0xFF3A5BC7),
        Color(if (dark) 0xFF435DB1 else 0xFFABBDF9),
    )

@Composable
internal fun beaconTagColors(tone: BeaconTagTone = BeaconTagTone.NEUTRAL): BeaconTagColors =
    tagColors(MaterialTheme.colorScheme.background.luminance() < .5f, tone)

@Composable
internal fun beaconChoiceColors(selected: Boolean): BeaconTagColors {
    val scheme = MaterialTheme.colorScheme
    return if (miuix()) miuixChoiceColors(scheme.background.luminance() < .5f, selected)
    else BeaconTagColors(
        if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh,
        if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        scheme.outline,
    )
}
