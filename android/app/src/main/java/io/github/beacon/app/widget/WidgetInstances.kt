package io.github.beacon.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.SizeF

enum class WidgetFormat(val label: String, val width: Int, val height: Int) {
    SQUARE("2×2", 180, 180), WIDE("4×2", 360, 180);
    fun component(context: Context) = ComponentName(context, if(this==WIDE) BeaconWideStateWidget::class.java else BeaconStateWidget::class.java)
}

object WidgetInstances {
    fun ids(context: Context): IntArray = WidgetFormat.entries.flatMap {
        AppWidgetManager.getInstance(context).getAppWidgetIds(it.component(context)).toList()
    }.distinct().toIntArray()

    fun format(context: Context, id: Int): WidgetFormat =
        if(AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider?.className==BeaconWideStateWidget::class.java.name) WidgetFormat.WIDE else WidgetFormat.SQUARE

    fun previewSize(context: Context, id: Int, format: WidgetFormat): SizeF {
        val options=AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        if(id==AppWidgetManager.INVALID_APPWIDGET_ID||options.isEmpty) return SizeF(format.width.toFloat(),format.height.toFloat())
        val sizes=WidgetHostSizes.resolve(options,context.resources.configuration.orientation,format.width,format.height)
        return if(context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE) sizes.maxBy{it.width} else sizes.minBy{it.width}
    }
}
