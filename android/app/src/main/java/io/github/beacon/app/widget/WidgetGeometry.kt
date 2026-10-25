package io.github.beacon.app.widget

import kotlin.math.max
import kotlin.math.min

/** Shared dp geometry for validation, previews and desktop. Text never shares a chart region. */
object WidgetGeometry {
    fun inset(width: Int, height: Int): Float = (min(width,height)*.075f).coerceIn(12f,20f)
    fun scale(width: Int,height: Int): Float = min(width/300f,height/220f).coerceIn(.75f,1.15f)

    data class Nested(
        val sideLegend:Boolean, val centerX:Float, val centerY:Float, val radius:Float,
        val stroke:Float, val legendX:Float, val legendY:Float, val legendWidth:Float,
        val legendHeight:Float, val showValues:Boolean,
    )

    fun nested(width: Int,top:Float,bottom:Float,pad:Float,count:Int,labelScale:Float=1f,strokeScale:Float=1f):Nested {
        require(count in 2..3)
        val areaW=max(1f,width-2*pad);val areaH=max(1f,bottom-top)
        val side=areaW>=218f && areaW>areaH*1.35f
        val gap=10f
        val legendH=if(side)areaH else (if(areaH>=112f)32f else 17f)*labelScale
        val chartW=if(side)areaW*.55f else areaW
        val chartH=if(side)areaH else max(1f,areaH-legendH-gap)
        // Include the outer stroke in the measured diameter; no clipping at cell boundaries.
        val radius=max(1f,min(chartW-gap,chartH)/2/1.12f)
        val stroke=min(radius*.19f*strokeScale,radius/(count*1.65f))
        return Nested(side,pad+chartW/2,top+chartH/2,radius,stroke,
            if(side)pad+chartW+gap else pad,if(side)top else top+chartH+gap,
            if(side)areaW-chartW-gap else areaW,legendH,side||areaH>=112f)
    }
}
