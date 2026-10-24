package io.github.beacon.app.domain.policy

import org.junit.Assert.*
import org.junit.Test

class BarkEncryptionValidationTest {
    @Test fun acceptsAllAesKeySizes() {
        listOf(16, 24, 32).forEach { assertTrue(BarkEncryptionValidation.valid("k".repeat(it), "i".repeat(16))) }
    }
    @Test fun checksUtf8BytesRatherThanCharacters() {
        assertTrue(BarkEncryptionValidation.valid("密".repeat(8), "i".repeat(16)))
        assertFalse(BarkEncryptionValidation.valid("密".repeat(16), "i".repeat(16)))
        assertFalse(BarkEncryptionValidation.valid("k".repeat(16), "向".repeat(16)))
    }
    @Test fun rejectsIncompleteAndInvalidLengths() {
        assertFalse(BarkEncryptionValidation.valid("", ""))
        assertFalse(BarkEncryptionValidation.valid("k".repeat(15), "i".repeat(16)))
        assertFalse(BarkEncryptionValidation.valid("k".repeat(16), "i".repeat(15)))
    }
}
