package io.github.beacon.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared geometry for both themes; compact metadata keeps its own smaller radius.
 *
 * Two families live here on purpose:
 * - Named insets ([PageInset], [CardInset], [SectionGap], …) say *what* the space is for, so a page
 *   can be re-spaced without hunting through every call site.
 * - The grid steps ([Gap2] … [Gap12]) are for inline rhythm inside one row, where no better name
 *   exists. Prefer the named inset when one fits.
 *
 * Sizes are literals rather than a theme lookup because both UI styles (Material 3 and MIUIX) share
 * the same geometry; only colour and shape differ between them.
 */
object BeaconUiMetrics {
    val PageInset = 16.dp
    val PageTopInset = 12.dp
    val PageBottomInset = 24.dp
    val CardInset = 16.dp
    val SectionGap = 12.dp
    val SettingsGroupGap = 20.dp
    val GroupTitleGap = 8.dp
    val PreferenceInset = 4.dp
    val NavigationMinHeight = 62.92.dp
    val NavigationVerticalInset = 4.84.dp
    val FieldMinHeight = 61.6.dp
    val FieldVerticalInset = 13.2.dp

    /** List search field: 36dp pairs with the 14sp search text so the pill is not mostly padding. */
    val SearchFieldMinHeight = 36.dp
    val SearchFieldVerticalInset = 6.dp
    val SearchFieldLeadingInset = 14.dp
    val SearchFieldIconGap = 10.dp
    val SearchFieldIconSize = 18.dp
    /**
     * The clear button fills the field's height so the whole rounded cap is tappable; a smaller
     * circle inside a 36dp pill would leave the reachable area below the accessibility minimum.
     */
    val SearchFieldClearSize = 36.dp
    val SearchFieldClearInset = 9.dp

    /**
     * Message cards were spaced like settings sections, so a row of them read as a stack of panels.
     * The list is a stream of equal items, so its shell is tighter than a section and the space
     * between cards barely exceeds the inset inside one.
     */
    val MessageCardVerticalInset = 10.dp
    val MessageCardGap = 8.dp

    /** The glyph shrinks; the button keeps its 32dp hit area rather than following it down. */
    val MessageCardActionSize = 32.dp
    val MessageCardActionIconSize = 20.dp

    /** The unread marker, sized to sit centred on the title's first line. */
    val UnreadDotSize = 6.dp
    val CardRadius = 16.dp

    /** Count badges are small pills; one radius and inset pair keeps them identical everywhere. */
    val BadgeRadius = 6.dp
    val BadgeVerticalInset = 3.dp

    /** Filter pills use one shape value so the two chip sites cannot drift apart. */
    val PillShape = RoundedCornerShape(percent = 50)
    val ActionMinWidth = 64.dp
    val ActionRadius = 12.dp
    val ActionShape = RoundedCornerShape(ActionRadius)
    val DialogActionTextSize = 13.sp
    val ControlMinHeight = 48.dp

    /** Inline spacing steps, smallest first. */
    val Gap2 = 2.dp
    val Gap4 = 4.dp
    val Gap6 = 6.dp
    val Gap8 = 8.dp
    val Gap10 = 10.dp
    val Gap12 = 12.dp
    val Gap16 = 16.dp

    /** Status rows lead with a square icon tile; the tile, not the glyph, sets the row height. */
    val StatusIconSize = 48.dp

    /** Square icon sizes used next to text. */
    val IconSmall = 16.dp
    val IconInline = 18.dp
    val IconMedium = 24.dp
}
