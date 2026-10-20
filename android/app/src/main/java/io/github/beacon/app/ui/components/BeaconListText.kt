package io.github.beacon.app.ui.components

import androidx.compose.ui.unit.sp

/**
 * Type for the message list, which carries the densest copy the app renders.
 *
 * A row is read as a stack: title, optional subtitle, preview, then metadata. At the theme's 16sp
 * title and 14sp preview the two read as equal paragraphs, so the title could not lead and the
 * preview could not afford a third line. The stack steps down instead — title 15sp semibold on the
 * surface colour, subtitle 13sp at [SubtitleAlpha], preview 13sp on the quiet variant colour — and
 * the preview settles at [PreviewLines] lines that are actually readable.
 *
 * These are Sp literals rather than theme overrides because the detail screen, the dialogs and the
 * settings cards keep the standard Material scale; only this list is dense enough to need its own.
 */
internal object BeaconListText {
    val TitleSize = 15.sp
    val TitleLineHeight = 20.sp
    val SubtitleSize = 13.sp
    val SubtitleLineHeight = 18.sp
    val PreviewSize = 13.sp
    val PreviewLineHeight = 18.sp

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
