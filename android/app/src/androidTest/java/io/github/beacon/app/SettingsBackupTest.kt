package io.github.beacon.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.*
import io.github.beacon.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupTest {
    @Test
    fun migrateExportAndAtomicImportPreserveHistoryOutsideSettings() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "backup-test-${java.util.UUID.randomUUID()}."
        val files = mutableSetOf<String>()
        val context =
            object : ContextWrapper(base) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    base.getSharedPreferences(prefix + name, mode).also { files.add(prefix + name) }
            }
        try {
            context
                .getSharedPreferences("receive_plan_v1", 0)
                .edit()
                .putString("mode", "rules")
                .putBoolean("realtime_wifi", false)
                .commit()
            val state = PollingState(PollingConfig(true, 30, true, true), revision = 7)
            PollingStateStore(context).save(state)
            CleanupPreferenceStore(context)
                .save(CleanupSettings(CleanupPolicy(7, 1000, true), true))
            val rules =
                ReceivePlan(
                    ReceiveMode.RULES,
                    true,
                    false,
                    true,
                    22 * 60,
                    7 * 60,
                    false,
                    true,
                    30,
                    180,
                )
            ReceivePlanStore(context).save(rules)
            val backup = SettingsBackup(context)
            val payload = backup.export()
            assertFalse(payload.contains("receive_token"))
            assertFalse(org.json.JSONObject(payload).has("enabled"))
            assertEquals(ReceiveMode.RULES, ReceivePlanStore(context).load().mode)
            val before = context.getSharedPreferences("general_settings_v1", 0).all
            assertTrue(runCatching { backup.restore(payload.dropLast(1), state) }.isFailure)
            assertEquals(before, context.getSharedPreferences("general_settings_v1", 0).all)
            val paused = backup.restore(payload, state)
            assertFalse(paused.config.enabled)
            assertEquals(8L, paused.revision)
            assertFalse(CleanupPreferenceStore(context).settings().automatic)
            assertEquals(paused, PollingStateStore(context).load())
            assertEquals(rules, ReceivePlanStore(context).load())
            assertEquals(payload, backup.export())
        } finally {
            files.forEach { base.deleteSharedPreferences(it) }
        }
    }
}
