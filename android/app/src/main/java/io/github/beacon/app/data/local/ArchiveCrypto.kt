package io.github.beacon.app.data.local

import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable password encryption. Authenticate the complete archive before parsing or writing. */
object ArchiveCrypto {
    const val MAX_BYTES = 32 * 1024 * 1024
    private val magic = "BEACONBK1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 210_000

    fun readBounded(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "Archive too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(password.size in 8..256)
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            val bytes =
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                SecretKeySpec(bytes, "AES")
            } finally {
                bytes.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        require(payload.size <= MAX_BYTES - 53)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = magic + salt + nonce
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(payload)
    }

    fun decrypt(archive: ByteArray, password: CharArray): ByteArray {
        require(archive.size in 53..MAX_BYTES && archive.copyOfRange(0, 9).contentEquals(magic))
        val header = archive.copyOfRange(0, 37)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(password, archive.copyOfRange(9, 25)),
            GCMParameterSpec(128, archive.copyOfRange(25, 37)),
        )
        cipher.updateAAD(header)
        return cipher.doFinal(archive, 37, archive.size - 37)
    }
}
