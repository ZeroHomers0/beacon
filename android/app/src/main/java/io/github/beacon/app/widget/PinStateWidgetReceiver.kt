package io.github.beacon.app.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.beacon.app.data.local.StateCache
import org.json.JSONObject

/** A pin result supplies the real host ID; configuration never uses a shared pending slot. */
class PinStateWidgetReceiver: BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val raw=intent.getStringExtra("config")?:return
        if(id==AppWidgetManager.INVALID_APPWIDGET_ID||raw.toByteArray().size>32768)return
        val config=runCatching{JSONObject(raw)}.getOrNull()?:return
        StateCache(context).use{it.preference("widget:$id",config.toString())}
        BeaconStateWidget.refresh(context,intArrayOf(id))
    }
}
