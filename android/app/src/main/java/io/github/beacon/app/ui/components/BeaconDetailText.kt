package io.github.beacon.app.ui.components

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared article typography for rendered Markdown, source text and detail metadata. */
internal object BeaconDetailText {
    val PageInset = 20.dp
    val MaxWidth = 640.dp
    val SectionGap = 24.dp
    val ParagraphGap = 12.dp
    val TitleSize = 24.sp
    val TitleLineHeight = 34.sp
    val SubtitleSize = 15.sp
    val SubtitleLineHeight = 23.sp
    val MetadataSize = 12.sp
    val MetadataLineHeight = 18.sp
    val BodySize = 16.sp
    val BodyLineHeight = 26.sp
    val CodeSize = 13.sp
    val HeadingMultipliers = floatArrayOf(1.25f, 1.125f, 1.0625f, 1f, 1f, 1f)
    val BlockMargin = 12.dp
    val QuoteWidth = 3.dp
    val PageBottom = 32.dp
}
