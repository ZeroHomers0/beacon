package io.github.beacon.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import io.github.beacon.app.R
import io.github.beacon.app.data.local.StateCache
import org.json.JSONObject

open class BeaconStateWidget: AppWidgetProvider() {
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action==Intent.ACTION_MY_PACKAGE_REPLACED){
            val manager=AppWidgetManager.getInstance(context)
            refresh(context,manager.getAppWidgetIds(ComponentName(context,javaClass)))
        }else super.onReceive(context,intent)
    }
    override fun onUpdate(context:Context, manager:AppWidgetManager, ids:IntArray){refresh(context,ids)}
    override fun onAppWidgetOptionsChanged(context:Context, manager:AppWidgetManager, id:Int, options:Bundle){refresh(context,intArrayOf(id))}
    override fun onDeleted(context:Context, ids:IntArray){StateCache(context).use{db->ids.forEach{db.preference("widget:$it","");db.preference("widget-signature:$it","");db.preference("widget-last:$it","");db.preference("widget-valid:$it","")}};StateRefreshReceiver.schedule(context)}
    override fun onRestored(context:Context, old:IntArray, new:IntArray){StateCache(context).use{db->old.indices.forEach{db.preference("widget:${new[it]}",db.preference("widget:${old[it]}"))}};refresh(context,new)}
    companion object {
        private val trailing=java.util.concurrent.Executors.newSingleThreadScheduledExecutor{task->Thread(task,"beacon-widget-refresh").apply{isDaemon=true}}
        private var scheduled:java.util.concurrent.ScheduledFuture<*>?=null
        private var scheduledAt=Long.MAX_VALUE
        @Synchronized private fun schedule(context:Context,delay:Long){
            val at=android.os.SystemClock.elapsedRealtime()+delay
            if(at>=scheduledAt)return
            scheduled?.cancel(false);scheduledAt=at
            scheduled=trailing.schedule({synchronized(this){scheduledAt=Long.MAX_VALUE;scheduled=null};refresh(context.applicationContext)},delay,java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        private val bitmaps=object:android.util.LruCache<String,android.graphics.Bitmap>(8*1024*1024){override fun sizeOf(key:String,value:android.graphics.Bitmap)=value.byteCount}
        @Synchronized fun refresh(context:Context, ids:IntArray?=null,source:String?=null,identities:Set<String>?=null){
            WidgetPerformance.requests.incrementAndGet()
            val manager=AppWidgetManager.getInstance(context);val targets=ids?:WidgetInstances.ids(context)
            // Incoming state is already durable. Never rasterize a hidden desktop on data pushes.
            // Initial host requests can still populate missing RemoteViews.
            if(ids==null&&!context.getSystemService(android.os.PowerManager::class.java).isInteractive){
                StateRefreshReceiver.schedule(context)
                return
            }
            StateCache(context).use{cache->for(id in targets){
                if(!context.getSystemService(android.os.PowerManager::class.java).isInteractive&&cache.preference("widget-signature:$id").isNotEmpty())continue
                val config=runCatching{JSONObject(cache.preference("widget:$id"))}.getOrElse{JSONObject()}
                if(source!=null&&(config.optString("source")!=source||identities!=null&&config.optString("identity") !in identities))continue
                val row=cache.find(config.optString("source"),config.optString("identity"))
                val options=manager.getAppWidgetOptions(id);val format=WidgetInstances.format(context,id)
                config.put("format",format.name).put("_offline",!WidgetHealth.online(context))
                val sizes=WidgetHostSizes.resolve(options,context.resources.configuration.orientation,format.width,format.height)
                val dark=when(config.optString("background")){"light"->false;"dark"->true;else->context.resources.configuration.uiMode and 0x30 == 0x20}
                val now=System.currentTimeMillis()
                val status=row?.optString("status")?:""
                val metrics=context.resources.displayMetrics
                val bounds=if(android.os.Build.VERSION.SDK_INT>=30)context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds else android.graphics.Rect(0,0,metrics.widthPixels,metrics.heightPixels)
                // Budget all responsive variants together, below the platform's 1.5-screen cap.
                val pixelBudget=(bounds.width().toDouble()*bounds.height()*1.0).coerceIn(1.0,Int.MAX_VALUE.toDouble())
                val area=sizes.sumOf{it.width.toDouble()*it.height}
                val rasterDensity=kotlin.math.min(metrics.density.toDouble(),kotlin.math.sqrt(pixelBudget/area)).toFloat()
                val signature="display-v7:"+android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT,0)+":"+java.util.TimeZone.getDefault().id+":"+context.resources.configuration.locales.toLanguageTags()+":"+rasterDensity+":"+sizes.joinToString()+":"+dark+":"+WidgetPerformance.displayKey(row,config)
                val last=cache.preference("widget-last:$id").toLongOrNull()?:0L
                if(cache.preference("widget-signature:$id")==signature){WidgetPerformance.skipped.incrementAndGet();continue}
                if(ids==null&&source!=null&&row!=null&&cache.preference("widget-valid:$id")=="1"&&status !in listOf("completed","failed","cancelled")&&now-last<WidgetPerformance.interval(config,row)){schedule(context,(WidgetPerformance.interval(config,row)-(now-last)).coerceAtLeast(250));continue}
                fun render(size:android.util.SizeF):RemoteViews {
                    val key=signature+":"+size
                    val bitmap=bitmaps.get(key)?.also{WidgetPerformance.reused.incrementAndGet()}?:WidgetRenderer.render(row,config,size.width.toInt(),size.height.toInt(),dark,rasterDensity,pixelBudget.toInt()).also{
                        bitmaps.put(key,it);WidgetPerformance.renders.incrementAndGet();WidgetPerformance.pixels.addAndGet(it.width.toLong()*it.height)
                    }
                    return RemoteViews(context.packageName,R.layout.state_widget).apply{
                        setImageViewBitmap(R.id.widget_image,bitmap)
                        setContentDescription(R.id.widget_image,row?.optString("body")?:"Beacon 数据，长按桌面组件编辑")
                        setOnClickPendingIntent(R.id.widget_image,null)
                    }
                }
                val views=if(android.os.Build.VERSION.SDK_INT>=31&&sizes.size>1)RemoteViews(sizes.associateWith{render(it)})else render(sizes.first())
                manager.updateAppWidget(id,views)
                cache.preference("widget-valid:$id",if(row==null)"0"else"1");cache.preference("widget-signature:$id",signature);cache.preference("widget-last:$id",now.toString())
            }}
            StateRefreshReceiver.schedule(context)
        }
    }
}

class BeaconWideStateWidget: BeaconStateWidget()
class BeaconMediumStateWidget: BeaconStateWidget()
class BeaconLargeStateWidget: BeaconStateWidget()
