package io.github.beacon.app.domain.model

/** Shared validation bounds. Byte limits refer to UTF-8, not character counts. */
object MessageLimits {
    const val ID_CHARACTERS = 128
    const val TITLE_BYTES = 1024
    const val BODY_BYTES = 65536
    const val NAME_BYTES = 128
    const val BARK_BYTES = 100000
    const val REQUEST_BYTES = 512 * 1024
    const val ACTION_COUNT = 1

    fun textFits(value: String, maxBytes: Int): Boolean {
        if (value.length > maxBytes) return false
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                index += 2
            } else {
                if (Character.isLowSurrogate(character)) return false
                index++
            }
        }
        return value.toByteArray(Charsets.UTF_8).size <= maxBytes
    }
    const val SOURCE_NAME_CHARACTERS = 40
    const val MUTATION_BATCH_SIZE = 100
}
