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
    override fun onDeleted(context:Context, ids:IntArray){StateCache(context).use{db->ids.forEach{db.preference("widget:$it","")}}}
    override fun onRestored(context:Context, old:IntArray, new:IntArray){StateCache(context).use{db->old.indices.forEach{db.preference("widget:${new[it]}",db.preference("widget:${old[it]}"))}};refresh(context,new)}
    companion object {
        private val pending=java.util.concurrent.atomic.AtomicBoolean(false)
        private val trailing=java.util.concurrent.Executors.newSingleThreadScheduledExecutor{task->Thread(task,"beacon-widget-refresh").apply{isDaemon=true}}
        private fun schedule(context:Context){if(pending.compareAndSet(false,true))trailing.schedule({pending.set(false);refresh(context.applicationContext)},5,java.util.concurrent.TimeUnit.SECONDS)}

        fun refresh(context:Context, ids:IntArray?=null){
            val manager=AppWidgetManager.getInstance(context);val targets=ids?:WidgetInstances.ids(context)
            StateCache(context).use{cache->val rows=cache.all();for(id in targets){
                val config=runCatching{JSONObject(cache.preference("widget:$id"))}.getOrElse{JSONObject()}
                val row=rows.firstOrNull{it.optString("local_source")==config.optString("source")&&it.optString("local_identity")==config.optString("identity")}
                val options=manager.getAppWidgetOptions(id);val format=WidgetInstances.format(context,id)
                config.put("format",format.name)
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
                val signature="raster-v2:"+rasterDensity+":"+config.toString()+":"+sizes.joinToString()+":"+dark+":"+row?.optLong("revision")+":"+(row==null)+":"+(row?.optLong("stale_at_ms") in 1..now)+":"+(row?.optLong("expires_at_ms") in 1..now)
                val last=cache.preference("widget-last:$id").toLongOrNull()?:0L
                if(ids==null&&cache.preference("widget-signature:$id")==signature)continue
                if(ids==null&&row!=null&&status !in listOf("completed","failed","cancelled")&&now-last<5000){schedule(context);continue}
                fun render(size:android.util.SizeF):RemoteViews {
                    val bitmap=WidgetRenderer.render(row,config,size.width.toInt(),size.height.toInt(),dark,rasterDensity,pixelBudget.toInt())
                    return RemoteViews(context.packageName,R.layout.state_widget).apply{
                        setImageViewBitmap(R.id.widget_image,bitmap)
                        setContentDescription(R.id.widget_image,row?.optString("body")?:"Beacon 状态，长按桌面组件编辑")
                        setOnClickPendingIntent(R.id.widget_image,null)
                    }
                }
                val views=if(android.os.Build.VERSION.SDK_INT>=31&&sizes.size>1)RemoteViews(sizes.associateWith{render(it)})else render(sizes.first())
                manager.updateAppWidget(id,views)
                cache.preference("widget-signature:$id",signature);cache.preference("widget-last:$id",now.toString())
            }}
        }
    }
}

class BeaconWideStateWidget: BeaconStateWidget()
class BeaconMediumStateWidget: BeaconStateWidget()
class BeaconLargeStateWidget: BeaconStateWidget()
