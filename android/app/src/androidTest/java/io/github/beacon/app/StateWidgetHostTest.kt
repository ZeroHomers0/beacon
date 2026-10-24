package io.github.beacon.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.ImageView
import android.graphics.drawable.BitmapDrawable
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.widget.BeaconStateWidget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StateWidgetHostTest {
    @Test fun twoNativeHostInstancesKeepIndependentBindings(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val manager=AppWidgetManager.getInstance(context)
        val host=AppWidgetHost(context,20421024)
        val source="f".repeat(64)
        val ids=mutableListOf<Int>()
        val row=JSONObject().put("schema_version",1).put("application_id","").put("topic_key","host:test").put("revision",1).put("title","双实例验收").put("body","短周期24% 长周期62%").put("accepted_at_ms",System.currentTimeMillis()).put("expires_at_ms",System.currentTimeMillis()+600000).put("blocks",JSONArray("""[{"kind":"progress","id":"short","label":"短周期","value":24,"max":100,"unit":"%"},{"kind":"progress","id":"long","label":"长周期","value":62,"max":100,"unit":"%"}]"""))
        StateCache(context).use{it.forget(source);it.apply(source,listOf(row),"host",true)}
        try{
            instrumentation.runOnMainSync{host.startListening()}
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try{
                for(metric in listOf("short","long")){
                    val id=host.allocateAppWidgetId();ids.add(id)
                    val options=Bundle().apply{putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,180);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,180);putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,180);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,180)}
                    if(android.os.Build.VERSION.SDK_INT>=31)options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,arrayListOf(android.util.SizeF(180f,180f),android.util.SizeF(300f,110f)))
                    assertTrue("bind actual AppWidgetHost",manager.bindAppWidgetIdIfAllowed(id,ComponentName(context,BeaconStateWidget::class.java),options))
                    val config=JSONObject().put("template","single_ring").put("source",source).put("identity",":host:test").put("metrics",JSONArray(listOf(metric)))
                    StateCache(context).use{it.preference("widget:$id",config.toString())}
                }
            }finally{instrumentation.uiAutomation.dropShellPermissionIdentity()}
            val views=ids.map{id->var view:android.appwidget.AppWidgetHostView?=null;instrumentation.runOnMainSync{view=host.createView(context,id,manager.getAppWidgetInfo(id))};requireNotNull(view)}
            BeaconStateWidget.refresh(context,ids.toIntArray())
            instrumentation.waitForIdleSync()
            val deadline=android.os.SystemClock.elapsedRealtime()+5000
            while(views.any{it.findViewById<ImageView>(R.id.widget_image)?.drawable !is BitmapDrawable}&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(20)
            val bitmaps=views.map{(it.findViewById<ImageView>(R.id.widget_image).drawable as BitmapDrawable).bitmap}
            assertTrue("host receives physical pixels, not a dp-sized thumbnail",bitmaps[0].width>=180*context.resources.displayMetrics.density*.9f)
            val a=IntArray(bitmaps[0].width*bitmaps[0].height);val b=IntArray(bitmaps[1].width*bitmaps[1].height)
            bitmaps[0].getPixels(a,0,bitmaps[0].width,0,0,bitmaps[0].width,bitmaps[0].height)
            bitmaps[1].getPixels(b,0,bitmaps[1].width,0,0,bitmaps[1].width,bitmaps[1].height)
            assertFalse("each host must show its own metric",a.contentEquals(b))
            host.deleteAppWidgetId(ids[0]);assertNotNull(manager.getAppWidgetInfo(ids[1]))
            StateCache(context).use{assertTrue(it.all().any{row->row.optString("local_source")==source})}
        }finally{
            instrumentation.runOnMainSync{host.stopListening();host.deleteHost()}
            StateCache(context).use{cache->cache.forget(source);ids.forEach{cache.preference("widget:$it","")}}
        }
    }
}
