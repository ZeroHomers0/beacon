package io.github.beacon.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

internal object WidgetTapIntent {
    fun intent(context:Context,id:Int,config:JSONObject):Intent? =
        if(config.optString("tap_action")!="detail"||config.optString("identity").isBlank())null
        else Intent(context,WidgetContentActivity::class.java)
            .setData(Uri.parse("beacon://widget-content/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)

    fun pending(context:Context,id:Int,config:JSONObject):PendingIntent? = intent(context,id,config)?.let {
        PendingIntent.getActivity(context,id,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
