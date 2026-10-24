package io.github.beacon.app.domain.policy

object BarkEncryptionValidation {
    fun valid(key: String, iv: String): Boolean =
        key.toByteArray(Charsets.UTF_8).size in listOf(16, 24, 32) &&
            iv.toByteArray(Charsets.UTF_8).size == 16
}
