package io.github.beacon.app.widget

import android.app.*
import android.content.*
import io.github.beacon.app.data.local.StateCache

/** One non-wakeup alarm for the earliest bound state's freshness/expiry boundary. */
class StateRefreshReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val result=goAsync()
        executor.execute{try{
            BeaconStateWidget.refresh(context,source=intent.getStringExtra("source"),identities=intent.getStringArrayListExtra("identities")?.toSet())
            StateNotifications.refresh(context)
            StateChanges.ui(context)
        }finally{result.finish()}}
    }
    companion object {
        private val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        fun request(context:Context){context.sendBroadcast(Intent(context,StateRefreshReceiver::class.java))}
        @Synchronized fun schedule(context:Context){
            val alarm=context.getSystemService(AlarmManager::class.java)
            val pending=PendingIntent.getBroadcast(context,7901,Intent(context,StateRefreshReceiver::class.java).setAction("beacon.STATE_DEADLINE"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val now=System.currentTimeMillis()
            val next=StateCache(context).use{cache->
                val bound=WidgetInstances.ids(context).toList().mapNotNull{runCatching{org.json.JSONObject(cache.preference("widget:$it"))}.getOrNull()}.map{it.optString("source") to it.optString("identity")}.toSet()
                (bound+cache.notificationBindings()).mapNotNull{cache.find(it.first,it.second)}
                    .flatMap{listOf(it.optLong("stale_at_ms"),it.optLong("expires_at_ms"))}.filter{it>now}.minOrNull()
            }
            if(next==null)alarm.cancel(pending)else alarm.set(AlarmManager.RTC,next,pending)
        }
    }
}
