package io.github.beacon.app.data.local

import org.junit.Assert.*
import org.junit.Test

class SettingsBackupTest {
    private val valid =
        """{"format":"beacon_settings","version":1,"interval":15,"unmetered":false,"charging":false,"mode":"rules","realtime_charging":true,"realtime_wifi":false,"fresh_minutes":5,"outdated":"SILENT","lockscreen_preview":false,"quiet_enabled":false,"quiet_start":1320,"quiet_end":480,"quiet_weekdays":127,"quiet_behavior":"SILENT","days":30,"max_messages":10000,"keep_unread":true}"""

    @Test
    fun validAndUnknownVersionBoundaries() {
        assertEquals("rules", SettingsBackup.validate(valid).getString("mode"))
        rejects(valid.replace("\"version\":1", "\"version\":2"))
        rejects(valid.replace("\"mode\":\"rules\"", "\"mode\":\"unknown\""))
        rejects(valid.replace("\"interval\":15", "\"interval\":1"))
        rejects(valid.replace("\"max_messages\":10000", "\"max_messages\":0"))
    }

    @Test
    fun StrictShapeTypesAndSize() {
        rejects(valid.dropLast(1) + ",\"receive_token\":\"secret\"}")
        rejects(valid.replace("\"version\":1", "\"version\":1,\"version\":1"))
        rejects(valid.replace("\"unmetered\":false", "\"unmetered\":\"false\""))
        rejects(valid.replace("\"interval\":15", "\"interval\":\"15\""))
        rejects(valid.replace("\"format\"", "format"))
        rejects(valid + " {}")
        rejects(" ".repeat(4097) + valid)
    }

    @Test
    fun oldBackupsDefaultTo60AndV3PreservesOnlySupportedHeartbeats() {
        val old = SettingsBackup.validate(valid)
        assertEquals(60, old.getInt("heartbeat_seconds"))
        val v2 = old.put("version", 2).apply { remove("heartbeat_seconds") }.toString()
        assertEquals(60, SettingsBackup.validate(v2).getInt("heartbeat_seconds"))
        val v3 = SettingsBackup.validate(v2).put("version", 3)
        for (seconds in io.github.beacon.app.domain.model.RealtimeHeartbeat.options) {
            assertEquals(
                seconds,
                SettingsBackup.validate(v3.put("heartbeat_seconds", seconds).toString())
                    .getInt("heartbeat_seconds"),
            )
        }
        rejects(v3.put("heartbeat_seconds", 181).toString())
        rejects(v3.put("heartbeat_seconds", "60").toString())
        v3.remove("heartbeat_seconds")
        rejects(v3.toString())
    }

    private fun rejects(value: String) {
        assertTrue(
            "Invalid backup accepted",
            runCatching { SettingsBackup.validate(value) }.isFailure,
        )
    }
}
