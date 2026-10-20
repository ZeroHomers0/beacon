package io.github.beacon.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import io.github.beacon.app.domain.policy.tagToneIndex

/**
 * Card metadata is a background texture, never a highlight: every tag has to sit next to a title
 * that owns the row, so the hues are desaturated, close in lightness, and printed at a low alpha
 * over the card instead of at full strength.
 *
 * Eight buckets, two slots (application, group). A push gateway sees a handful of applications and
 * a handful of groups, so eight is enough to keep them apart without inventing a hue per name.
 */
private val Hues =
    listOf(
        0xFF3B6EA5, // 蓝
        0xFF2F7A6B, // 青绿
        0xFF5B4E9E, // 靛
        0xFF9A5A2C, // 赭
        0xFF8A4A6B, // 紫红
        0xFF4C6B2F, // 橄榄
        0xFF3C6B78, // 灰蓝
        0xFF7A5230, // 褐
    )

/** The tint behind one metadata label, printed over the card so the result stays opaque. */
@Composable
@ReadOnlyComposable
internal fun beaconTagContainer(label: String, slot: Int): Color {
    val scheme = MaterialTheme.colorScheme
    val darker = scheme.surface.luminance() < .5f
    val hue = Color(Hues[tagToneIndex(label, slot)])
    // A dark surface already carries the contrast, so the same wash would read as a highlight;
    // both modes therefore share the hue list and differ only in how much of it is laid down.
    // The first pass was a whisper at 10%; a chip has to be identifiable across the room to be
    // worth tinting at all, so this is about twice that, still short of a saturated badge.
    val alpha = if (darker) .16f else .19f
    return hue.copy(alpha = alpha).compositeOver(scheme.surfaceContainerLow)
}

/**
 * Text on a tinted chip. The neutral tone code used `onSurfaceVariant`, which drops under 4.5:1
 * once a hue sits behind 10sp text; a tinted chip therefore switches to `onSurface` and keeps the
 * label legible instead of turning it into decoration.
 */
@Composable
@ReadOnlyComposable
internal fun beaconTagContent(toneSlot: Int): Color =
    if (toneSlot < 0) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurface.copy(alpha = .82f)
