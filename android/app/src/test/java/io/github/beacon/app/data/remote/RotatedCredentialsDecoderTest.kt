package io.github.beacon.app.data.remote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RotatedCredentialsDecoderTest {
    private fun payload() =
        JSONObject()
            .put("server_id", "a".repeat(64))
            .put("device_id", "b".repeat(64))
            .put("receive_token", "c".repeat(64))
            .put("push_key", "d".repeat(64))

    @Test
    fun cliJsonDecodesWithoutSecretInStringRepresentation() {
        val decoded = RotatedCredentialsDecoder.decode(payload().toString(2))
        assertEquals("a".repeat(64), decoded.serverId)
        assertEquals("c".repeat(64), decoded.receiveToken)
        assertFalse(decoded.toString().contains(decoded.receiveToken))
        assertFalse(decoded.toString().contains(decoded.pushKey))
    }

    @Test
    fun rejectsWrongShapeOrReusedPushCredentialWithoutEchoingInput() {
        val invalid =
            listOf(
                payload().put("receive_token", 123),
                payload().put("push_key", "c".repeat(64)),
                payload().put("device_id", "B".repeat(64)),
                payload().put("extra", "private-data"),
                payload().also { it.remove("push_key") },
            )
        invalid.forEach { value ->
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    RotatedCredentialsDecoder.decode(value.toString())
                }
            assertFalse(error.message!!.contains("private-data"))
            assertFalse(error.message!!.contains("c".repeat(64)))
        }
    }

    @Test
    fun rejectsTrailingDataDuplicateFieldsAndLenientAndroidSyntax() {
        val valid = payload().toString()
        listOf(
                valid + " trailing",
                "[$valid]",
                valid.dropLast(1) + ",\"server_id\":\"${"a".repeat(64)}\"}",
                valid.replace("\"server_id\"", "server_id"),
                valid.replace('"', '\''),
                valid.replace(",", ";"),
            )
            .forEach { value ->
                assertThrows(IllegalArgumentException::class.java) {
                    RotatedCredentialsDecoder.decode(value)
                }
            }
    }

    @Test
    fun oversizedPayloadNeverParses() {
        assertThrows(IllegalArgumentException::class.java) {
            RotatedCredentialsDecoder.decode(" ".repeat(4096) + payload())
        }
    }
}
