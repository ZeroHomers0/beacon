package io.github.beacon.app.feature.settings

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerAdminOverviewTest {
    private fun overview(): JSONObject =
        JSONObject(
            """
        {"server_id":"server-1","version":"1.0.0","protocol":1,"admin_protocol":1,
        "devices":[{"device_id":"phone-1","name":"Phone","revoked":false}],
        "applications":[{"id":"app-1","device_id":"phone-1","name":"NAS","default_priority":8,"revoked":true}],
        "storage":{"messages":12,"pending":3,"database_allocated_bytes":4096}}
    """
        )

    @Test
    fun decodesServerCountsDeviceStatusAndApplicationPermissions() {
        val result = ServerAdminViewModel.parseOverview(overview())
        assertEquals("1.0.0", result.version)
        assertEquals(3L, result.pending)
        assertEquals("phone-1", result.devices.single().id)
        assertTrue(result.applications.single().revoked)
        assertEquals(8, result.applications.single().priority)
        assertNull(result.secret)
    }

    @Test
    fun supportsExistingAdminApiWithoutNewVersionFields() {
        val json =
            overview().apply {
                remove("version")
                remove("protocol")
                remove("admin_protocol")
            }
        assertEquals(1, ServerAdminViewModel.parseOverview(json).protocol)
    }

    @Test
    fun rejectsUnknownManagementProtocol() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerAdminViewModel.parseOverview(overview().put("admin_protocol", 2))
        }
    }
}
