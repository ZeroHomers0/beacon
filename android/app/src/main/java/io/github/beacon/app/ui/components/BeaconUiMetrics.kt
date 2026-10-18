package io.github.beacon.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

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
    val CardInset = 16.dp
    val SectionGap = 12.dp
    val SettingsGroupGap = 20.dp
    val GroupTitleGap = 8.dp
    val PreferenceInset = 4.dp
    val NavigationMinHeight = 57.2.dp
    val NavigationVerticalInset = 4.4.dp
    val FieldMinHeight = 56.dp
    val FieldVerticalInset = 12.dp

    /** List search field: 85% of the former 52dp row, so the pinned header costs a line less. */
    val SearchFieldMinHeight = 44.dp
    val SearchFieldVerticalInset = 10.dp
    val CardRadius = 16.dp

    /** Two-line label/detail status rows keep a stable height while their detail text changes. */
    val StatusDetailMinHeight = 64.dp

    /** Count badges are small pills; one radius and inset pair keeps them identical everywhere. */
    val BadgeRadius = 6.dp
    val BadgeVerticalInset = 3.dp

    /** Filter pills use one shape value so the two chip sites cannot drift apart. */
    val PillShape = RoundedCornerShape(percent = 50)
    val ControlMinHeight = 48.dp

    /**
     * Count chips share the search row, so they take the field's height: the row reads as one set of
     * pills instead of a tall field with two short tags beside it.
     */
    val CountChipMinWidth = 56.dp
    val CountChipHeight = SearchFieldMinHeight
    val CountChipPadding = 14.dp

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
