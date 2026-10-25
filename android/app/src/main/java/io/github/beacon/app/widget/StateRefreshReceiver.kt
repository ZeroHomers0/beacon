package io.github.beacon.app.widget

import android.app.*
import android.content.*
import io.github.beacon.app.data.local.StateCache

/** One non-wakeup alarm for the earliest bound state's freshness/expiry boundary. */
class StateRefreshReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        observeScreen(context.applicationContext)
        val result=goAsync()
        if(intent.action==NOTIFY_RETRY){
            executor.execute{try{StateNotifications.refresh(context.applicationContext)}finally{result.finish()}}
            return
        }
        synchronized(lock) {
            results.add(result)
            val source=intent.getStringExtra("source")
            val identities=intent.getStringArrayListExtra("identities")?.toSet()
            if(source==null) full=true
            else if(!full) {
                if(identities==null) changes[source]=null
                else if(!changes.containsKey(source)) changes[source]=identities.toMutableSet()
                else changes[source]?.addAll(identities)
            }
            if(!queued){queued=true;executor.schedule({drain(context.applicationContext)},150,java.util.concurrent.TimeUnit.MILLISECONDS)}
        }
    }
    companion object {
        private const val NOTIFY_RETRY="beacon.STATE_NOTIFICATION_RETRY"
        private val executor=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        private val lock=Any()
        private var queued=false
        private var full=false
        private val changes=linkedMapOf<String,MutableSet<String>?>()
        private val results=mutableListOf<PendingResult>()
        private var observing=false
        private fun observeScreen(context:Context)=synchronized(lock){
            if(!observing){
                androidx.core.content.ContextCompat.registerReceiver(context,object:BroadcastReceiver(){
                    override fun onReceive(c:Context,i:Intent){request(c)}
                },IntentFilter(Intent.ACTION_SCREEN_ON),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
                observing=true
            }
        }
        private fun drain(context:Context){
            val batch:Map<String,Set<String>?>;val all:Boolean;val pending:List<PendingResult>
            synchronized(lock){
                batch=changes.mapValues{it.value?.toSet()};all=full;pending=results.toList()
                changes.clear();results.clear();full=false;queued=false
            }
            try{
                if(all)BeaconStateWidget.refresh(context)
                else batch.forEach{(source,ids)->BeaconStateWidget.refresh(context,source=source,identities=ids)}
                StateNotifications.refresh(context,if(all)null else batch)
                StateChanges.ui(context)
            }finally{pending.forEach{it.finish()}}
        }
        fun after(context:Context,delay:Long){
            synchronized(lock){
                val at=android.os.SystemClock.elapsedRealtime()+delay
                if(at>=retryAt)return
                retry?.cancel(false);retryAt=at
                retry=executor.schedule({synchronized(lock){retryAt=Long.MAX_VALUE;retry=null};context.sendBroadcast(Intent(context,StateRefreshReceiver::class.java).setAction(NOTIFY_RETRY))},delay.coerceAtLeast(1),java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        private var retry:java.util.concurrent.ScheduledFuture<*>?=null
        private var retryAt=Long.MAX_VALUE
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
