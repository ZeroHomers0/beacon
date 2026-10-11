package io.github.beacon.app.domain.model

/** Shared validation bounds. Byte limits refer to UTF-8, not character counts. */
object MessageLimits {
    const val ID_CHARACTERS = 128
    const val TITLE_BYTES = 1024
    const val BODY_BYTES = 65536
    const val SOURCE_NAME_CHARACTERS = 40
    const val MUTATION_BATCH_SIZE = 100
}
