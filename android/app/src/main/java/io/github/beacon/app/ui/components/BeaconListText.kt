package io.github.beacon.app.ui.components

import androidx.compose.ui.unit.sp

/** Shared information hierarchy for message, management and topic lists. */
internal object BeaconListText {
    val MetadataSize = 12.sp
    val MetadataLineHeight = 16.sp
    val ValueSize = 18.sp
    val ValueLineHeight = 24.sp
    val TitleSize = 16.sp
    val TitleLineHeight = 22.sp
    val SubtitleSize = 14.sp
    val SubtitleLineHeight = 20.sp
    val PreviewSize = 14.sp
    val PreviewLineHeight = 20.sp

    /** Two lines of preview: enough to judge a message, short enough to keep a card scannable. */
    const val PreviewLines = 2

    /**
     * Three levels, not two: the title is [onSurface], the preview is [onSurfaceVariant], and the
     * subtitle sits between them. At full strength a subtitle read as a second title; the preview
     * is already the quiet colour, so the subtitle takes the middle step instead.
     */
    const val SubtitleAlpha = .75f

    /** Search text, sized to sit inside the 36dp field beside its 18dp leading icon. */
    val SearchSize = 14.sp
    val SearchLineHeight = 20.sp
}
