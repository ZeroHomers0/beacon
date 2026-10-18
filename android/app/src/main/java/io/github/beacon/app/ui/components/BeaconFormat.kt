package io.github.beacon.app.ui.components

import java.text.NumberFormat

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
