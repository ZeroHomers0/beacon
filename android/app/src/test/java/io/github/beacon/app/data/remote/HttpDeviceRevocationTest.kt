package io.github.beacon.app.data.remote

import org.junit.Assert.*
import org.junit.Test

class HttpDeviceRevocationTest {
    private val server = "a".repeat(64)
    private val device = "b".repeat(64)
    private val valid = """{"server_id":"$server","device_id":"$device","revoked":true}"""

    @Test
    fun acceptsOnlyMatchingIdentityWithBooleanConfirmation() {
        HttpDeviceRevocation.verifyConfirmation(valid, server, device)
        HttpDeviceRevocation.verifyConfirmation(" \n$valid\n", server, device)
        assertThrows(DeviceRevocationUnconfirmed::class.java) {
            HttpDeviceRevocation.verifyConfirmation(valid, "c".repeat(64), device)
        }
        assertThrows(DeviceRevocationUnconfirmed::class.java) {
            HttpDeviceRevocation.verifyConfirmation(valid, server, "c".repeat(64))
        }
    }

    @Test
    fun rejectsPermissiveJsonFalseConfirmationAndOversizedReplies() {
        listOf(
                valid.replace("true", "false"),
                valid.replace("true", "\"true\""),
                valid.replace("true", "1"),
                valid.dropLast(1) + ",\"revoked\":true}",
                valid.dropLast(1) + ",\"private\":\"secret\"}",
                valid.replace("\"revoked\"", "revoked"),
                valid + "{}",
                valid.replace("\"$device\"", "null"),
                " ".repeat(4097) + valid,
            )
            .forEach { payload ->
                val error =
                    assertThrows(DeviceRevocationUnconfirmed::class.java) {
                        HttpDeviceRevocation.verifyConfirmation(payload, server, device)
                    }
                assertNull(error.message)
            }
    }
}
