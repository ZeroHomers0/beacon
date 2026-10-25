package io.github.beacon.app

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.widget.StateNotifications
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StateNotificationPowerTest {
    @Test fun revisionsDoNotRepostAndTerminalBypassesThrottling() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")).use{it.readBytes()}
        val source="9".repeat(64);val identity=":power-notification";val key="$source:$identity"
        val manager=context.getSystemService(NotificationManager::class.java)
        val data=JSONObject().put("schema_version",1).put("application_id","").put("topic_key","power-notification")
            .put("title","功耗测试").put("body","进行中").put("state_type","task").put("status","running")
            .put("revision",1).put("priority",2).put("expires_at_ms",System.currentTimeMillis()+600000)
            .put("blocks",JSONArray("""[{"id":"p","kind":"progress","label":"进度","value":40,"max":100}]"""))
        val quiet=io.github.beacon.app.data.local.QuietHoursStore(context)
        val previousQuiet=quiet.load();quiet.save(previousQuiet.copy(enabled=false))
        try {
            StateCache(context).use { cache ->
                cache.preference("notify:$key","1")
                cache.apply(source,listOf(data),"one",true)
                StateNotifications.refresh(context)
                val first=cache.preference("posted:$key");assertTrue(first.isNotEmpty())
                cache.preference("posted-at:$key","1")
                cache.apply(source,listOf(JSONObject(data.toString()).put("revision",2)),"two",false)
                StateNotifications.refresh(context)
                assertEquals("Revision-only updates must not post", "1",cache.preference("posted-at:$key"))
                val interactive=context.getSystemService(android.os.PowerManager::class.java).isInteractive
                cache.preference("posted-at:$key",(System.currentTimeMillis()-(if(interactive)4500L else 59500L)).toString())
                val changed=JSONObject(data.toString()).put("revision",3).put("body","新进度")
                cache.apply(source,listOf(changed),"three",false)
                StateNotifications.refresh(context)
                assertEquals("Intermediate updates are coalesced",first,cache.preference("posted:$key"))
                val deadline=android.os.SystemClock.elapsedRealtime()+5000
                while(cache.preference("posted:$key")==first&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(50)
                assertTrue("Latest update must be delivered by the trailing timer without another push",cache.preference("posted:$key").contains("新进度"))
                cache.preference("posted-at:$key",System.currentTimeMillis().toString())
                cache.apply(source,listOf(JSONObject(changed.toString()).put("revision",4).put("status","completed")),"four",false)
                StateNotifications.refresh(context)
                assertEquals("completed",JSONObject(cache.preference("posted:$key")).getString("status"))
                assertTrue(manager.activeNotifications.any{it.tag=="state:$key"})
            }
        } finally {
            quiet.save(previousQuiet)
            StateCache(context).use{it.preference("notify:$key","");it.forget(source)}
            manager.cancel("state:$key",1)
        }
    }
}
