package io.github.beacon.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import io.github.beacon.app.domain.policy.tagToneIndex

/**
 * Card metadata uses a tinted background: every tag has to sit next to a title
 * that owns the row, so the hues carry enough chroma to be told apart at a glance without ever
 * competing with the text.
 *
 * Eight buckets, two slots (application, group). A push gateway sees a handful of applications and
 * a handful of groups, so eight is enough to keep them apart without inventing a hue per name.
 * Lightness is deliberately held level across the eight — the point is to name a thing, not to
 * rank it — so every one of them prints to within a few percent the same luminance.
 */
private val Hues =
    listOf(
        0xFF2C6BC4, // 蓝
        0xFF0E8C7E, // 青绿
        0xFF5C46D6, // 靛
        0xFFD2741A, // 琥珀
        0xFFC23F80, // 玫红
        0xFF4E9421, // 草绿
        0xFF10869E, // 青
        0xFFA85B2B, // 赭褐
    )

/** The tint behind one metadata label, printed over the card so the result stays opaque. */
@Composable
@ReadOnlyComposable
internal fun beaconTagContainer(label: String, slot: Int): Color {
    val scheme = MaterialTheme.colorScheme
    val darker = scheme.surface.luminance() < .5f
    val hue = Color(Hues[tagToneIndex(label, slot)])
    // A stronger wash makes neighbouring tag hues easier to distinguish in both themes.
    val alpha = if (darker) .38f else .40f
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
    else MaterialTheme.colorScheme.onSurface
