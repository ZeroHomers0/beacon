package io.github.beacon.app.data.local

import org.junit.Assert.*
import org.junit.Test

class ArchiveCryptoTest {
    @Test
    fun roundTripAndRandomizedEncryption() {
        val data = "消息历史".toByteArray()
        val password = "test-password".toCharArray()
        val archive = ArchiveCrypto.encrypt(data, password)
        assertArrayEquals(data, ArchiveCrypto.decrypt(archive, password))
        assertFalse(archive.contentEquals(ArchiveCrypto.encrypt(data, password)))
    }

    @Test
    fun wrongPasswordAndTamperingRejected() {
        val archive = ArchiveCrypto.encrypt("secret".toByteArray(), "password123".toCharArray())
        assertTrue(
            runCatching { ArchiveCrypto.decrypt(archive, "wrongpassword".toCharArray()) }.isFailure
        )
        archive[archive.lastIndex] = (archive.last().toInt() xor 1).toByte()
        assertTrue(
            runCatching { ArchiveCrypto.decrypt(archive, "password123".toCharArray()) }.isFailure
        )
    }

    @Test
    fun boundedReadsRejectOversizedFiles() {
        assertArrayEquals(
            byteArrayOf(1, 2),
            ArchiveCrypto.readBounded(byteArrayOf(1, 2).inputStream(), 2),
        )
        assertTrue(
            runCatching { ArchiveCrypto.readBounded(byteArrayOf(1, 2, 3).inputStream(), 2) }
                .isFailure
        )
        assertTrue(
            runCatching { ArchiveCrypto.encrypt(byteArrayOf(1), "short".toCharArray()) }.isFailure
        )
    }
}
