package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.IncomingMessage
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BarkContentTest {
    @Test
    fun compatibleCbcDecryptsAndKeepsMedia() {
        val key = "1234567890123456"
        val iv = "1111111111111111"
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(), "AES"),
            IvParameterSpec(iv.toByteArray()),
        )
        val payload = """{"title":"标题","body":"正文","image":"https://example.com/a.png"}"""
        val metadata =
            JSONObject()
                .put(
                    "ciphertext",
                    Base64.getEncoder().encodeToString(cipher.doFinal(payload.toByteArray())),
                )
                .put("iv", iv)
                .toString()
        val message = IncomingMessage("server", "id", "", "加密消息", bark = metadata)
        val decoded = BarkContent.resolve(message, key to iv)
        assertEquals("标题", decoded.title)
        assertEquals("正文", decoded.body)
        assertEquals("https://example.com/a.png", JSONObject(decoded.bark).getString("image"))
        assertFalse(decoded.bark.contains("ciphertext"))
        assertEquals(metadata, BarkContent.resolve(message, "bad-key" to iv).bark)
        assertEquals(2, BarkContent.resolve(message, null).priority)
    }

    @Test
    fun nonHttpsMediaRejected() {
        assertTrue(
            runCatching { BarkContent.validate(JSONObject().put("image", "http://example.com/a")) }
                .isFailure
        )
        assertTrue(
            runCatching {
                BarkContent.validate(JSONObject().put("icon", "https://user@example.com/a"))
            }
                .isFailure
        )
    }
}
