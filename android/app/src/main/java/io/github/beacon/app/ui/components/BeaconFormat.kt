package io.github.beacon.app.ui.components

import java.text.NumberFormat
import java.util.Locale

/** Every numeric readout uses this dash while the value is unknown, so pages agree on "no data". */
const val BeaconUnknownValue = "—"

/**
 * Counts read the same on every page: grouped thousands in the reader's locale, and the shared dash
 * when the number has not arrived. Passing the raw number straight into a label rendered `1234`
 * next to a grouped `1.2 GB`, which made the two halves of the same card look unrelated.
 *
 * Takes [Number] because the same card mixes `Int` counters with `Long` database totals.
 */
fun formatCount(value: Number?): String =
    value?.let { NumberFormat.getIntegerInstance().format(it.toLong()) } ?: BeaconUnknownValue

/**
 * The same count in the fewest characters that stay unambiguous, for gutters with no room to grow
 * (the message filters next to the search field): `128` stays exact, `1280` becomes `1.2k` and
 * `12800` becomes `13k`. Screen readers never see this form, they read [formatCount].
 */
fun formatCompactCount(value: Long): String = when {
    value < 1000 -> value.toString()
    value < 10_000 -> String.format(Locale.getDefault(), "%.1fk", value / 1000f)
    value < 1_000_000 -> "${value / 1000}k"
    else -> String.format(Locale.getDefault(), "%.1fm", value / 1_000_000f)
}
