package io.github.beacon.app.widget

import android.graphics.*
import org.json.JSONObject
import kotlin.math.*

/** Shared by native desktop RemoteViews and the configuration preview. No network assets. */
object WidgetRenderer {
    val templates = listOf("numbers", "horizontal_bars", "vertical_bars", "single_ring", "nested_rings", "ring_grid", "segmented_ring")
    val names = listOf("纯数字", "横向进度条", "纵向柱状图", "单环", "嵌套环", "独立小环", "多色分段环")
    fun render(row: JSONObject?, config: JSONObject, width: Int, height: Int, dark: Boolean, density: Float = 1f, maxPixels: Int = 2097152): Bitmap {
        val w=width.coerceAtLeast(1);val h=height.coerceAtLeast(1)
        val factor=min(density.takeIf{it.isFinite()&&it>0}?.toDouble()?:1.0,sqrt(maxPixels.coerceAtLeast(1).toDouble()/(w.toDouble()*h)))
        val bitmap=Bitmap.createBitmap((w*factor).toInt().coerceAtLeast(1),(h*factor).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        bitmap.density=Bitmap.DENSITY_NONE
        val c=Canvas(bitmap);c.scale(bitmap.width.toFloat()/w,bitmap.height.toFloat()/h)
        val scale=min(w/300f,h/220f).coerceAtLeast(.75f);val pad=max(12f,min(w,h)* (40f/440f))
        val fg=if(dark) Color.rgb(233,237,244) else Color.rgb(23,25,29)
        val muted=if(dark) Color.rgb(166,176,190) else Color.rgb(99,108,118)
        val track=if(dark) Color.rgb(51,59,72) else Color.rgb(226,231,238)
        val palette=if(dark) intArrayOf(0xffa9c7ff.toInt(),0xffc7aeff.toInt(),0xff6ddba8.toInt(),0xffffc47c.toInt()) else intArrayOf(0xff3478f6.toInt(),0xff7466d9.toInt(),0xff16754d.toInt(),0xffa45a06.toInt())
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(s:String,x:Float,y:Float,size:Float=14f,color:Int=fg,bold:Boolean=false,right:Float=w-pad,cap:Int=8,align:Paint.Align=Paint.Align.LEFT){
            if(right<=x)return
            p.style=Paint.Style.FILL;p.color=color;p.textSize=size*scale.coerceAtLeast(.75f)
            p.typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
            p.textAlign=align
            val fitted=WidgetTextLimits.fit(s,p,right-x,cap)
            val metrics=p.fontMetrics
            c.save();c.clipRect(x,y+metrics.ascent,right,y+metrics.descent)
            c.drawText(fitted,if(align==Paint.Align.RIGHT)right else if(align==Paint.Align.CENTER)(x+right)/2 else x,y,p)
            c.restore();p.textAlign=Paint.Align.LEFT
        }

        p.color=if(config.optString("background")=="transparent") Color.argb(225,if(dark)25 else 248,if(dark)28 else 249,if(dark)34 else 250) else if(dark)Color.rgb(25,28,34)else Color.rgb(248,249,250)
        c.drawRoundRect(RectF(0f,0f,w.toFloat(),h.toFloat()),min(w,h)*.15f,min(w,h)*.15f,p)
        val template=config.optString("template","horizontal_bars")
        if((template=="ring_grid"&&(width<240||height<240))||(template=="nested_rings"&&width<240)) {text("请放大小组件",pad,h*.55f,16f,color=muted);return bitmap}
        val now=System.currentTimeMillis();val expired=row!=null&&(row.optLong("expires_at_ms") in 1..now);val stale=row!=null&&(row.optLong("stale_at_ms") in 1..now)
        text(config.optString("title").ifBlank{row?.optString("title")?:"Beacon 状态"},pad,pad+13*scale,13f,bold=true,cap=12)
        if(row==null||expired){text(if(expired)"数据已失效" else "等待数据 / 配置组件",pad,h*.55f,18f,color=muted);return bitmap}
        val selected=WidgetConfigRules.selected(row,config)
        val error=WidgetConfigRules.error(row,config)
        if(error!=null){text(error,pad,h*.55f,14f,color=muted);return bitmap}
        fun color(index:Int,b:JSONObject):Int=if(stale)muted else if(config.optString("color")=="semantic")when(b.optString("tone")){"critical"->if(dark)0xffff9296.toInt()else 0xffc5343a.toInt();"warning"->palette[3];else->palette[0]}else palette[index%palette.size]
        fun ratio(b:JSONObject):Float{val value=b.optDouble("value");val min=b.optDouble("min",0.0);val max=b.optDouble("max");return if(value.isFinite()&&max.isFinite()&&max>min)((value-min)/(max-min)).toFloat().coerceIn(0f,1f)else Float.NaN}
        fun value(b:JSONObject):String=WidgetTextLimits.number(b.optDouble("value"))+WidgetTextLimits.cap(b.optString("unit"),4)

        fun toneColor(tone:String):Int=when(tone){"critical"->if(dark)0xffff9296.toInt()else 0xffc5343a.toInt();"warning"->palette[3];else->palette[2]}
        fun arc(x:Float,y:Float,r:Float,v:Float,col:Int,stroke:Float,block:JSONObject?=null){
            p.style=Paint.Style.STROKE;p.strokeWidth=stroke;p.strokeCap=Paint.Cap.ROUND;p.color=track;c.drawCircle(x,y,r,p)
            if(v.isFinite()&&v>0){
                val bands=if(config.optBoolean("bands"))block?.optJSONArray("bands")else null
                if(bands!=null&&bands.length()>0){var previous=block?.optDouble("min",0.0)?:0.0;val max=block?.optDouble("max")?:1.0;val min=previous
                    p.strokeCap=Paint.Cap.BUTT
                    for(i in 0 until bands.length()){val band=bands.getJSONObject(i);val until=band.optDouble("until");val start=((previous-min)/(max-min)).toFloat();val end=min(v,((until-min)/(max-min)).toFloat());if(end>start){p.color=toneColor(band.optString("tone"));c.drawArc(RectF(x-r,y-r,x+r,y+r),-90f+start*360,(end-start)*360,false,p)};previous=until}
                }else{p.color=col;c.drawArc(RectF(x-r,y-r,x+r,y+r),-90f,v*360,false,p)}
            };p.style=Paint.Style.FILL
        }

        val compactRing=template=="single_ring"&&height<150;val top=pad+28*scale;val bottom=h-pad-(if(compactRing)0f else 16*scale);val contentH=bottom-top;val type=config.optString("template","horizontal_bars")
        if(selected.isEmpty()){text("暂无绑定指标",pad,h*.55f,18f,color=muted);return bitmap}
        if(type=="nested_rings"&&contentH/selected.size<36*scale){text("请放大小组件",pad,h*.55f,16f,color=muted);return bitmap}
        val tooSmall=(type=="horizontal_bars"&&contentH/selected.size<30*scale)||(type=="numbers"&&contentH/(if(selected.size>2)2 else 1)<40*scale)||(type=="vertical_bars"&&(contentH<90*scale||selected.size>3&&width<240))
        if(tooSmall){text("请放大以展示全部指标",pad,h*.55f,14f,color=muted,cap=16);return bitmap}
        when(type){
            "numbers"->{val count=min(selected.size,4);val cols=if(count>1)2 else 1;val cellH=contentH/(if(count>2)2 else 1);for(i in 0 until count){val x=pad+(i%cols)*(w-2*pad)/cols;val y=top+(i/cols)*cellH;text(value(selected[i]),x,y+cellH*.52f,min(if(count==1)48f else 30f,cellH/scale*.42f),bold=true,right=x+(w-2*pad)/cols-10*scale,cap=12);text(selected[i].optString("label"),x,y+cellH*.86f,min(12f,cellH/scale*.18f),muted,right=x+(w-2*pad)/cols-10*scale)}}
            "horizontal_bars"->{val count=min(selected.size,4);for(i in 0 until count){val b=selected[i];val rowH=contentH/count;val y=top+i*rowH;val r=ratio(b);text(b.optString("label"),pad,y+rowH*.3f,12f,right=w*.60f,cap=8);text(value(b),w*.65f,y+rowH*.3f,17f,bold=true,cap=12,align=Paint.Align.RIGHT);val rect=RectF(pad,y+rowH*(if(count<=3).58f else .62f),w-pad,y+rowH*(if(count<=3).86f else .82f));p.color=track;c.drawRoundRect(rect,7f,7f,p);if(r.isFinite()){val bands=if(config.optBoolean("bands"))b.optJSONArray("bands")else null;if(bands!=null&&bands.length()>0){val minValue=b.optDouble("min",0.0);val maxValue=b.optDouble("max");var previous=minValue;for(j in 0 until bands.length()){val band=bands.getJSONObject(j);val until=band.optDouble("until");val start=((previous-minValue)/(maxValue-minValue)).toFloat();val end=min(r,((until-minValue)/(maxValue-minValue)).toFloat());if(end>start){p.color=toneColor(band.optString("tone"));c.drawRect(pad+(w-2*pad)*start,rect.top,pad+(w-2*pad)*end,rect.bottom,p)};previous=until}}else{p.color=color(i,b);rect.right=pad+(w-2*pad)*r;c.drawRoundRect(rect,7f,7f,p)}}}}
            "vertical_bars"->{val count=min(selected.size,6);val compatible=selected.take(count).all{it.optString("unit")==selected[0].optString("unit")&&it.optDouble("min",0.0)==selected[0].optDouble("min",0.0)&&it.optDouble("max")==selected[0].optDouble("max")};if(!compatible)text("指标范围不同，请重新配置",pad,top+40*scale,14f,muted)else for(i in 0 until count){val b=selected[i];val r=ratio(b);val cell=(w-2*pad)/count;val x=pad+i*cell;p.color=color(0,b);if(r.isFinite())c.drawRoundRect(RectF(x+cell*(if(count<=3).10f else .15f),bottom-25*scale-(contentH-55*scale)*r,x+cell*(if(count<=3).90f else .85f),bottom-25*scale),5f,5f,p);text(value(b),x,bottom-30*scale-(contentH-55*scale)*(if(r.isFinite())r else 0f),10f,right=x+cell-4*scale,cap=8,align=Paint.Align.CENTER);text(b.optString("label"),x,bottom-5*scale,10f,right=x+cell-4*scale,cap=4,align=Paint.Align.CENTER)}}
            "single_ring","nested_rings"->{val count=if(type=="single_ring")1 else min(selected.size,3);val radius=min(if(count>1)(w*.64f-pad)/2.1f else w*.42f,contentH*.48f);val x=if(count>1)pad+radius else w/2f;val y=top+contentH/2;val stroke=radius*.18f;for(i in 0 until count){val b=selected[i];arc(x,y,radius-i*stroke*1.65f,ratio(b),color(i,b),stroke,b);if(count>1){text(b.optString("label"),w*.67f,top+(i+.22f)*contentH/count,11f,muted);text(value(b),w*.67f,top+(i+.82f)*contentH/count,23f,bold=true,cap=12)}};if(count==1){text(value(selected[0]),x-radius*.8f,y,min(30f,radius*.52f/scale),bold=true,right=x+radius*.8f,cap=10,align=Paint.Align.CENTER);text(selected[0].optString("label"),x-radius*.8f,y+min(23*scale,radius*.65f),min(11f,radius*.36f/scale),muted,right=x+radius*.8f,cap=6,align=Paint.Align.CENTER)}else text(config.optString("title","状态"),x-radius*.28f,y+5*scale,10f,right=x+radius*.28f,cap=5,align=Paint.Align.CENTER)}
            "ring_grid"->{val count=min(selected.size,4);for(i in 0 until count){val cellW=(w-2*pad)/2;val cellH=contentH/(if(count>2)2 else 1);val x=pad+cellW*(i%2+.5f);val y=top+cellH*(i/2+.5f);val radius=min(cellW,cellH)*.38f;arc(x,y,radius,ratio(selected[i]),color(i,selected[i]),radius*(if(count<=3).21f else .16f),selected[i]);text(value(selected[i]),x-radius*.62f,y,19f,bold=true,right=x+radius*.62f,cap=9,align=Paint.Align.CENTER);text(selected[i].optString("label"),x-radius*.62f,y+17*scale,9f,muted,right=x+radius*.62f,cap=4,align=Paint.Align.CENTER)}}
            "segmented_ring"->{val b=selected.first();val items=b.optJSONArray("items");val total=b.optDouble("total");val radius=min(w*.26f,contentH*.4f);val x=pad+radius;val y=top+contentH/2;p.style=Paint.Style.STROKE;p.strokeWidth=radius*(if((items?.length()?:0)<=3).26f else .2f);p.strokeCap=Paint.Cap.BUTT;p.color=track;c.drawCircle(x,y,radius,p);var start=-90f;if(items!=null&&total>0)for(i in 0 until min(items.length(),6)){val item=items.getJSONObject(i);val angle=(item.optDouble("value")/total*360).toFloat();p.color=palette[i%4];c.drawArc(RectF(x-radius,y-radius,x+radius,y+radius),start,angle,false,p);start+=angle};p.style=Paint.Style.FILL;text(WidgetTextLimits.number(total)+WidgetTextLimits.cap(b.optString("unit"),4),x-radius*.6f,y,18f,bold=true,right=x+radius*.6f,cap=10,align=Paint.Align.CENTER);if(items!=null)for(i in 0 until min(items.length(),6))text(WidgetTextLimits.cap(items.getJSONObject(i).optString("label"),4)+" "+WidgetTextLimits.number(items.getJSONObject(i).optDouble("value")),w*.6f,top+(i+1)*contentH/(items.length()+1),12f)}
            else->text("请选择模板",pad,top+30*scale,16f)
        }
        if(stale)text("数据已过期 · 最后采样 "+java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(row.optLong("observed_at_ms"))),pad,h-pad,11f,muted,cap=24)
        else if(!compactRing&&config.optBoolean("updated",true))text("更新 "+java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(row.optLong("accepted_at_ms"))),pad,h-pad,11f,muted,cap=24)
        return bitmap
    }
}
