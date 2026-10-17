package io.github.beacon.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.HeartbeatMemoryStore
import org.junit.Assert.*
import org.junit.Test

class HeartbeatMemoryTest {
    @Test
    fun memoryIsBoundedAndExpiredHintsAreNotReused() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val memoryName = "heartbeat-test-${java.util.UUID.randomUUID()}"
        val context =
            object : ContextWrapper(base) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    base.getSharedPreferences(memoryName, mode)
            }
        try {
            val memory = HeartbeatMemoryStore(context)
            assertEquals(60, memory.load("new"))
            memory.save("new", 300)
            assertEquals(300, memory.load("new"))
            repeat(20) { memory.save("network-$it", 180) }
            val preferences = base.getSharedPreferences(memoryName, 0)
            assertEquals(16, preferences.all.size)
            preferences
                .edit()
                .putString(
                    "expired",
                    org.json
                        .JSONObject()
                        .put("seconds", 300)
                        .put("at", System.currentTimeMillis() - 8 * 24 * 60 * 60_000L)
                        .toString(),
                )
                .commit()
            assertEquals(60, memory.load("expired"))
        } finally {
            base.deleteSharedPreferences(memoryName)
        }
    }
}
