package io.github.beacon.app.ui.components

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Type for the message detail screen, one step under the theme's Material scale.
 *
 * The list already runs a size down from the theme ([BeaconListText]); opening a row and landing on
 * a 22sp title made the detail screen feel like a different app. The detail title drops to
 * [TitleSize] and the subtitle to [SubtitleSize], which is what actually separates it from the
 * preview it came from: 16sp subtitle next to 16sp body was the reason the two read as one block.
 *
 * The body runs a step under the theme as well. 16sp is the comfortable floor for short prose, but
 * these bodies are digests: fifteen numbered lines plus a tip, and on a phone the line count is what
 * costs the reader, not the glyph size. [BodySize] buys back roughly a screen and a half of scroll
 * across a full digest while staying above the 14sp where CJK starts to smear.
 */
internal object BeaconDetailText {
    val TitleSize = 20.sp
    val TitleLineHeight = 27.sp
    val SubtitleSize = 14.sp
    val SubtitleLineHeight = 20.sp

    val BodySize = 15.sp
    val BodyLineHeight = 22.sp

    /** Code renders a step below prose; at body size CJK code spans read as bold body text. */
    val CodeSize = 13.sp

    /**
     * Markdown heading multipliers, replacing Markwon's HTML-spec defaults
     * (`{2, 1.5, 1.17, 1, .83, .67}`). At 2x, an `#` in a push body would render at 32sp — larger
     * than the message title above it, so the document would claim the message's hierarchy.
     */
    val HeadingMultipliers = floatArrayOf(1.5f, 1.3f, 1.15f, 1.05f, 1f, .9f)

    /** Markwon's default block margin is 24dp, which also indents lists and quotes far too deeply. */
    val BlockMargin = 14.dp
    val QuoteWidth = 3.dp

    /** The scroll container ends on the system gesture area; 12dp there reads as clipped. */
    val PageBottom = 24.dp
}
