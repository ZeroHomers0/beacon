package io.github.beacon.app.domain.model

/** Device free space is informational; it never authorizes extra deletion. */
enum class StoragePressure {
    UNKNOWN,
    NORMAL,
    LOW,
}

enum class CleanupExplanation {
    EMPTY,
    WITHIN_RULES,
    PROTECTED,
    READY,
}

object StorageHealth {
    const val LOW_SPACE_BYTES = 100L * 1024 * 1024

    fun pressure(availableBytes: Long): StoragePressure =
        when {
            availableBytes < 0 -> StoragePressure.UNKNOWN
            availableBytes <= LOW_SPACE_BYTES -> StoragePressure.LOW
            else -> StoragePressure.NORMAL
        }

    fun explanation(preview: CleanupPreview): CleanupExplanation =
        when {
            preview.total == 0L -> CleanupExplanation.EMPTY
            preview.planned > 0 -> CleanupExplanation.READY
            preview.protected > 0 -> CleanupExplanation.PROTECTED
            else -> CleanupExplanation.WITHIN_RULES
        }
}
