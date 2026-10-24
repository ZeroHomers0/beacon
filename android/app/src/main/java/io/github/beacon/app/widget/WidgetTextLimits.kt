package io.github.beacon.app.widget

import android.graphics.Paint
import kotlin.math.abs

/** Widget-only display limits. The original values remain available in the detail screen. */
object WidgetTextLimits {
    fun cap(value: String, max: Int): String {
        val clean=value.replace('\n',' ').replace('\r',' ')
        val count=clean.codePointCount(0,clean.length)
        return if(count<=max)clean else clean.substring(0,clean.offsetByCodePoints(0,(max-1).coerceAtLeast(0)))+"…"
    }
    fun fit(value: String, paint: Paint, width: Float, max: Int): String {
        var result=cap(value,max)
        if(paint.measureText(result)<=width)return result
        result=result.removeSuffix("…")
        while(result.isNotEmpty()&&paint.measureText(result+"…")>width) result=result.substring(0,result.offsetByCodePoints(result.length,-1))
        return if(paint.measureText(result+"…")<=width)result+"…"else ""
    }
    fun number(value: Double): String {
        if(!value.isFinite())return "—"
        if(value==value.toLong().toDouble()&&abs(value)<10000000)return value.toLong().toString()
        return if(abs(value) in .01..9999999.0)java.text.DecimalFormat("0.##",java.text.DecimalFormatSymbols(java.util.Locale.ROOT)).format(value)
        else String.format(java.util.Locale.ROOT,"%.3g",value)
    }
}
