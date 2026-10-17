package io.github.beacon.app.domain.policy

internal fun metadataLabelWidths(desired: List<Int>, available: Int): List<Int> {
    val widths = MutableList(desired.size) { 0 }
    var remaining = available.coerceAtLeast(0)
    desired.indices.sortedBy { desired[it] }.forEachIndexed { position, index ->
        val width = desired[index].coerceAtMost(remaining / (desired.size - position))
        widths[index] = width
        remaining -= width
    }
    return widths
}
