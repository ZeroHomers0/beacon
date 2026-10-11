package io.github.beacon.app.domain.policy

/**
 * Orders release versions so the app can tell whether the published build is newer than itself.
 *
 * A comparable version is a dotted run of numbers with an optional leading `v` and an optional
 * build suffix, as produced by a release tag: `1.0.1`, `v1.0.1`, `1.0.1-dev`. Anything else cannot
 * be ordered, and an unorderable version is never treated as an update — the badge stays off rather
 * than sending the user to a build that may be older.
 */
object ReleaseVersionPolicy {
    /** Four segments cover `major.minor.patch.build`; more than that is not a release tag. */
    private const val MAX_PARTS = 4

    /** The numeric segments of [version], or null when it is not an ordered release version. */
    fun parse(version: String): List<Int>? {
        val numeric =
            version
                .trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
        val parts = numeric.split('.')
        if (parts.size > MAX_PARTS) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** True only when [candidate] is strictly newer than [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        val published = parse(candidate) ?: return false
        val installed = parse(current) ?: return false
        for (index in 0 until maxOf(published.size, installed.size)) {
            val difference = (published.getOrNull(index) ?: 0) - (installed.getOrNull(index) ?: 0)
            if (difference != 0) return difference > 0
        }
        return false
    }
}
