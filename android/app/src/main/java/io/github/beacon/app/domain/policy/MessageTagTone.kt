package io.github.beacon.app.domain.policy

/**
 * Buckets a metadata label into one of [TAG_TONE_COUNT] colours.
 *
 * The application name and the group name sit next to each other on the same card, so the two have
 * to be told apart at a glance; two different applications have to be told apart as well. Deriving
 * the bucket from the text means the colour survives a restart and needs no stored mapping, and
 * giving the two slots different seeds keeps an application from sharing the exact colour of a
 * group that happens to have the same name.
 *
 * Only the bucket leaves this package: which hue each bucket maps to is a presentation decision
 * (see `ui/components/BeaconTagTone.kt`), and this file must stay free of the framework.
 */
internal const val TAG_TONE_COUNT = 8

/** The two metadata slots a card can tint. Two seeds keep their hashes from colliding. */
internal const val TAG_SLOT_APPLICATION = 0
internal const val TAG_SLOT_GROUP = 1

/**
 * FNV-1a over the code units, run through a finalizer, then trimmed to three bits.
 *
 * FNV-1a alone is a poor bucketer at this size. Its state is a single accumulator that only ever
 * shifts left by 24 bits per step, so a one-character input never reaches the high half of the
 * hash and `a`..`z` land in two buckets. The finalizer (`murmur3`'s `fmix32`) folds every input bit
 * across the whole word, after which both the top and bottom three bits use all eight buckets.
 *
 * The multiply overflows a signed long on purpose: Kotlin wraps, and the low 32 bits of a wrapped
 * product are the low 32 bits of the exact product, which is all the mask keeps.
 */
internal fun tagToneIndex(label: String, slot: Int): Int {
    var hash = 2166136261L
    hash = ((hash xor slot.toLong()) * 16777619L) and 0xFFFFFFFFL
    for (char in label) hash = ((hash xor char.code.toLong()) * 16777619L) and 0xFFFFFFFFL
    return (finalize(hash) ushr 29).toInt()
}

private fun finalize(value: Long): Long {
    var hash = value and 0xFFFFFFFFL
    hash = (hash xor (hash ushr 16)) and 0xFFFFFFFFL
    hash = (hash * 0x85EBCA6BL) and 0xFFFFFFFFL
    hash = (hash xor (hash ushr 13)) and 0xFFFFFFFFL
    hash = (hash * 0xC2B2AE35L) and 0xFFFFFFFFL
    return (hash xor (hash ushr 16)) and 0xFFFFFFFFL
}
