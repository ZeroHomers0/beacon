package io.github.beacon.app.widget

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import io.github.beacon.app.R
import io.github.beacon.app.data.local.StateCache
import org.json.JSONObject

class StateDismissReceiver: BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){val key=intent.getStringExtra("key")?:return;StateCache(context).use{it.preference("dismiss:$key","1")}}
}
object StateNotifications {
    @Synchronized fun refresh(context:Context,changes:Map<String,Set<String>?>?=null){
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val manager=context.getSystemService(NotificationManager::class.java)
        if(manager.getNotificationChannel("beacon_states")==null)manager.createNotificationChannel(NotificationChannel("beacon_states","状态更新",NotificationManager.IMPORTANCE_LOW))
        if(manager.getNotificationChannel("beacon_states_first")==null)manager.createNotificationChannel(NotificationChannel("beacon_states_first","状态首次提醒",NotificationManager.IMPORTANCE_DEFAULT))
        StateCache(context).use{cache->
            val bindings=cache.notificationBindings()
            val active=bindings.map{it.first+":"+it.second}.toSet()
            val activeNotifications=manager.activeNotifications
            activeNotifications.filter{it.tag?.startsWith("state:")==true&&it.tag!!.removePrefix("state:") !in active}.forEach{manager.cancel(it.tag,it.id)}
            val quiet=io.github.beacon.app.data.local.QuietHoursStore(context).load()
            val offline=!WidgetHealth.online(context)
            val interactive=context.getSystemService(android.os.PowerManager::class.java).isInteractive
            for((source,identity) in bindings){
                if(changes!=null&&(!changes.containsKey(source)||changes[source]?.let{identity !in it}==true))continue
                val key=source+":"+identity
                val row=cache.find(source,identity)
                if(row==null||row.optBoolean("_waiting")||row.optBoolean("_topic_deleted")){manager.cancel("state:$key",1);continue}
                if(cache.preference("notify:$key")!="1"||cache.preference("dismiss:$key")=="1") {manager.cancel("state:$key",1);continue}
                val now=System.currentTimeMillis();
                val priority=row.optInt("priority",5)
                if(priority<=1||quiet.active(now,java.time.ZoneId.systemDefault())&&quiet.behavior==io.github.beacon.app.domain.model.QuietHoursBehavior.SAVE_ONLY){manager.cancel("state:$key",1);continue}
if(row.optLong("expires_at_ms") in 1..now){manager.cancel("state:$key",1);continue}
                val stale=row.optLong("stale_at_ms") in 1..now
                val selected=cache.preference("primary:$key");val blocks=row.optJSONArray("blocks")
                val block=(0 until (blocks?.length()?:0)).mapNotNull{blocks?.optJSONObject(it)}.firstOrNull{it.optString("kind")=="progress"&&(selected.isEmpty()||it.optString("id")==selected)}
                val intent=Intent(context,StateWidgetActivity::class.java).putExtra("source",row.optString("local_source")).putExtra("identity",row.optString("local_identity")).putExtra("detail",true).setData(android.net.Uri.parse("beacon://state/"+android.net.Uri.encode(key)))
                val open=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val dismiss=PendingIntent.getBroadcast(context,0,Intent(context,StateDismissReceiver::class.java).setData(intent.data).putExtra("key",key),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val summary=(if(stale)"数据较旧 · " else if(offline)"网络未连通 · "else "")+row.optString("body")
                val action=io.github.beacon.app.data.remote.MessageActionsCodec.decode(row).firstOrNull()
                val status=row.optString("status")
                // Fingerprint exactly what this surface displays, independent of revisions/timestamps.
                val display=JSONObject().put("title",row.optString("title")).put("summary",summary)
                    .put("status",status).put("stale",stale).put("offline",offline)
                    .put("actionLabel",action?.displayTitle).put("actionUrl",action?.url)
                if(block!=null&&!stale&&status !in listOf("completed","failed","cancelled")){
                    val min=block.optDouble("min",0.0);val max=block.optDouble("max");val value=block.optDouble("value")
                    if(block.optBoolean("indeterminate"))display.put("indeterminate",true)
                    else if(max>min&&value.isFinite())display.put("progress",(((value-min)/(max-min))*10000).toInt().coerceIn(0,10000))
                }
                val signature=display.toString()
                val last=cache.preference("posted:$key")
                val exists=activeNotifications.any{it.tag=="state:$key"}
                if(last==signature&&exists)continue
                val lastAt=cache.preference("posted-at:$key").toLongOrNull()?:0L
                val interval=if(interactive)5000L else 60000L
                val terminal=status in listOf("completed","failed","cancelled")
                if(!terminal&&exists&&now>=lastAt&&now-lastAt<interval){StateRefreshReceiver.after(context,interval-(now-lastAt));continue}
                val channel=if(last.isEmpty()&&priority>=4&&!quiet.active(now,java.time.ZoneId.systemDefault()))"beacon_states_first"else"beacon_states"
                val builder=Notification.Builder(context,channel).setSmallIcon(R.drawable.ic_notification).setContentTitle(row.optString("title")).setContentText(summary).setStyle(Notification.BigTextStyle().bigText(summary)).setOnlyAlertOnce(true).setContentIntent(open).setDeleteIntent(dismiss).setVisibility(Notification.VISIBILITY_PRIVATE)
                if(block!=null&&!stale&&row.optString("status") !in listOf("completed","failed","cancelled")){if(block.optBoolean("indeterminate"))builder.setProgress(0,0,true)else{val min=block.optDouble("min",0.0);val max=block.optDouble("max");val value=block.optDouble("value");if(max>min&&value.isFinite())builder.setProgress(10000,(((value-min)/(max-min))*10000).toInt().coerceIn(0,10000),false)}}
                action?.let{action->
                    val link=Intent(Intent.ACTION_VIEW,android.net.Uri.parse(action.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val pending=PendingIntent.getActivity(context,0,link,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    builder.addAction(Notification.Action.Builder(null,action.displayTitle,pending).build())
                }
                manager.notify("state:$key",1,builder.build());cache.preference("posted:$key",signature);cache.preference("posted-at:$key",now.toString())
            }
        }
    }
}
