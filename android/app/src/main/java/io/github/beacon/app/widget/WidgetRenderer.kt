package io.github.beacon.app.widget

import android.graphics.*
import org.json.JSONObject
import kotlin.math.*

/** Shared by native desktop RemoteViews and the configuration preview. No network assets. */
object WidgetRenderer {
    val templates = listOf("numbers", "horizontal_bars", "vertical_bars", "single_ring", "nested_rings", "ring_grid", "segmented_ring", "text_list", "text", "sparkline")
    val names = listOf("纯数字", "横向进度条", "纵向柱状图", "单环", "嵌套环", "独立小环", "占比圆环", "文字列表", "整段文字", "迷你趋势图")
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
        val numberScale=WidgetTuning.factor(config,"number_scale");val labelScale=WidgetTuning.factor(config,"label_scale")
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

        p.color=if(config.optString("background")=="transparent") Color.TRANSPARENT else if(dark)Color.rgb(25,28,34)else Color.rgb(248,249,250)
        c.drawRoundRect(RectF(0f,0f,w.toFloat(),h.toFloat()),min(w,h)*.15f,min(w,h)*.15f,p)
        val template=config.optString("template","horizontal_bars")

        val now=System.currentTimeMillis();val expired=row!=null&&(row.optLong("expires_at_ms") in 1..now);val stale=row!=null&&(row.optLong("stale_at_ms") in 1..now)
        text(config.optString("title").ifBlank{row?.optString("title")?:"Beacon 数据"},pad,pad+13*scale,13f,bold=true,cap=12)
        if(row==null||expired){text(if(expired)"已过有效期" else if(config.optString("identity").isBlank())"尚未绑定主题"else"原内容已删除或未同步",pad,h*.55f,14f,color=muted,cap=24);return bitmap}
        val selected=WidgetConfigRules.selected(row,config)
        val error=WidgetConfigRules.layoutError(row,config,width,height)
        if(error!=null){text(error,pad,h*.55f,14f,color=muted);return bitmap}
        fun color(index:Int,b:JSONObject):Int=WidgetColors.resolve(config,b.optString("id")) ?: if(stale)muted else if(config.optString("color")=="semantic")when(b.optString("tone")){"critical"->if(dark)0xffff9296.toInt()else 0xffc5343a.toInt();"warning"->palette[3];else->palette[0]}else palette[index%palette.size]
        fun ratio(b:JSONObject):Float{val value=b.optDouble("value");val min=b.optDouble("min",0.0);val max=b.optDouble("max");return if(value.isFinite()&&max.isFinite()&&max>min)((value-min)/(max-min)).toFloat().coerceIn(0f,1f)else Float.NaN}
        fun value(b:JSONObject):String {
            val raw=b.optDouble("value");val decimals=config.optInt("decimals",-1)
            val number=if(decimals in 0..3&&raw.isFinite())String.format(java.util.Locale.ROOT,"%.${decimals}f",raw)else WidgetTextLimits.number(raw)
            val unit=WidgetTextLimits.cap(b.optString("unit"),4)
            val result=when(config.optString("unit_position","after")){"before"->unit+number;"hidden"->number;else->number+unit}
            val arrow=if(config.optBoolean("trend",true))when(b.optString("trend")){"up"->" ↑";"down"->" ↓";"flat"->" →";else->""}else""
            return result+arrow
        }

        fun toneColor(tone:String):Int=when(tone){"critical"->if(dark)0xffff9296.toInt()else 0xffc5343a.toInt();"warning"->palette[3];else->palette[2]}
        fun arc(x:Float,y:Float,r:Float,v:Float,col:Int,stroke:Float,block:JSONObject?=null){
            p.style=Paint.Style.STROKE;p.strokeWidth=stroke;p.strokeCap=Paint.Cap.ROUND;p.color=track;c.drawCircle(x,y,r,p)
            if(v.isFinite()&&v>0){
                val bands=if(config.optBoolean("bands")&&block?.let{WidgetColors.resolve(config,it.optString("id"))}==null)block?.optJSONArray("bands")else null
                if(bands!=null&&bands.length()>0){var previous=block?.optDouble("min",0.0)?:0.0;val max=block?.optDouble("max")?:1.0;val min=previous
                    p.strokeCap=Paint.Cap.BUTT
                    for(i in 0 until bands.length()){val band=bands.getJSONObject(i);val until=band.optDouble("until");val start=((previous-min)/(max-min)).toFloat();val end=min(v,((until-min)/(max-min)).toFloat());if(end>start){p.color=toneColor(band.optString("tone"));c.drawArc(RectF(x-r,y-r,x+r,y+r),-90f+start*360,(end-start)*360,false,p)};previous=until}
                }else{p.color=col;c.drawArc(RectF(x-r,y-r,x+r,y+r),-90f,v*360,false,p)}
            };p.style=Paint.Style.FILL
        }

        fun ringContent(b:JSONObject,x:Float,y:Float,radius:Float,stroke:Float,numberSize:Float,labelSize:Float){
            val inner=(radius-stroke/2-4*scale).coerceAtLeast(1f)
            val n=min(numberSize*scale,inner*.52f)*numberScale;val l=min(labelSize*scale,inner*.27f)*labelScale
            val gap=3*scale;val height=n*1.15f+l*1.15f+gap;val top=y-height/2
            fun line(caption:String,baseline:Float,size:Float,bold:Boolean,col:Int){
                p.textSize=size;p.typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
                val fm=p.fontMetrics;val vertical=max(abs(baseline+fm.ascent-y),abs(baseline+fm.descent-y))
                val half=(sqrt((inner*inner-vertical*vertical).coerceAtLeast(0f))-2*scale).coerceAtLeast(0f)
                text(caption,x-half,baseline,size/scale,col,bold,right=x+half,cap=128,align=Paint.Align.CENTER)
            }
            val ink=WidgetColors.resolve(config,b.optString("id"))?:fg
            line(value(b),top+n*.9f,n,true,ink)
            line(b.optString("label"),top+n*1.15f+gap+l*.85f,l,false,WidgetColors.resolve(config,b.optString("id"))?:muted)
        }

        val compactRing=template=="single_ring"&&height<150;val top=pad+28*scale;val bottom=h-pad-(if(compactRing)0f else 16*scale);val contentH=bottom-top;val type=config.optString("template","horizontal_bars")
        if(selected.isEmpty()&&type!="text"){text("暂无绑定内容",pad,h*.55f,18f,color=muted);return bitmap}
        val textSize=config.optInt("text_size",16).coerceIn(12,24).toFloat()
        val textInk=WidgetColors.resolve(config,if(template=="text")""else selected.firstOrNull()?.optString("id").orEmpty())?:if(stale)muted else fg
        when(type){
            "text_list"->{
                val items=selected.first().optJSONArray("items")?:org.json.JSONArray()
                val columns=if(config.optString("format") in listOf("WIDE","LARGE"))2 else 1
                val lines=config.optInt("list_lines",1).coerceIn(1,2)
                val lineH=textSize*scale*1.6f*lines
                val perColumn=(contentH/lineH).toInt().coerceAtLeast(1)
                val cellW=(w-2*pad)/columns
                val start=(config.optInt("list_start",1)-1).coerceIn(0,11)
                val limit=min(items.length()-start,min(perColumn*columns,config.optInt("list_count",12).coerceIn(1,12))).coerceAtLeast(0)
                for(i in 0 until limit){
                    val column=i/perColumn;val line=i%perColumn;val x=pad+column*cellW;val y=top+line*lineH
                    val caption=(if(config.optBoolean("list_rank",true))"${start+i+1}. "else "")+items.getJSONObject(start+i).optString("text")
                    val paint=android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=textInk;this.textSize=textSize*scale}
                    val layout=android.text.StaticLayout.Builder.obtain(caption,0,caption.length,paint,(cellW-(if(columns>1)8*scale else 0f)).toInt().coerceAtLeast(1))
                        .setIncludePad(false).setMaxLines(lines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
                    c.save();c.clipRect(x,y,x+cellW,y+lineH);c.translate(x,y);layout.draw(c);c.restore()
                }
            }
            "sparkline"->{
                val b=selected.first();val points=b.optJSONArray("points")?:org.json.JSONArray()
                val values=(0 until points.length()).map{points.getJSONObject(it).optDouble("value")}
                if(values.isNotEmpty()){
                    val minV=values.min();val maxV=values.max();val startAt=points.getJSONObject(0).optLong("at_ms");val endAt=points.getJSONObject(points.length()-1).optLong("at_ms")
                    text(b.optString("label"),pad,top+12*scale,12f*labelScale,WidgetColors.resolve(config,b.optString("id"))?:muted,cap=32)
                    text(value(JSONObject(b.toString()).put("value",values.last())),pad,top+46*scale,min(24f*numberScale,28f),color(0,b),bold=true,cap=16)
                    val chartTop=top+62*scale;val chartBottom=bottom-5*scale;val path=Path()
                    values.forEachIndexed{i,v->val at=points.getJSONObject(i).optLong("at_ms");val x=pad+(w-2*pad)*(if(endAt>startAt)(at-startAt).toFloat()/(endAt-startAt)else .5f);val y=chartBottom-(chartBottom-chartTop)*(if(maxV>minV)((v-minV)/(maxV-minV)).toFloat()else .5f);if(i==0){path.moveTo(x,y);if(values.size==1)path.lineTo(x+.01f,y)}else path.lineTo(x,y)}
                    p.color=color(0,b);p.strokeWidth=3*scale;p.style=Paint.Style.STROKE;p.strokeJoin=Paint.Join.ROUND;p.strokeCap=Paint.Cap.ROUND
                    c.save();c.clipRect(pad,chartTop,w-pad,bottom);c.drawPath(path,p);c.restore();p.style=Paint.Style.FILL
                }
            }
            "text"->{
                val paint=android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=textInk;this.textSize=textSize*scale;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
                val layout=android.text.StaticLayout.Builder.obtain(row.optString("body"),0,row.optString("body").length,paint,(w-2*pad).toInt().coerceAtLeast(1))
                    .setIncludePad(false).setLineSpacing(2*scale,1f).setMaxLines((contentH/(paint.fontSpacing+2*scale)).toInt().coerceAtLeast(1))
                    .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
                c.save();c.clipRect(pad,top,w-pad,bottom);c.translate(pad,top);layout.draw(c);c.restore()
            }
            "numbers"->{val count=min(selected.size,if(config.optString("format")=="LARGE")8 else 4);val cols=if(config.optString("format")=="MEDIUM"&&count<=3)count else if(count>1)2 else 1;val cellH=contentH/((count+cols-1)/cols);for(i in 0 until count){val x=pad+(i%cols)*(w-2*pad)/cols;val y=top+(i/cols)*cellH;text(value(selected[i]),x,y+cellH*.52f,min(if(count==1)48f else 30f,cellH/scale*.36f)*numberScale,color=color(i,selected[i]),bold=true,right=x+(w-2*pad)/cols-10*scale,cap=12);text(selected[i].optString("label"),x,y+cellH*.86f,min(12f,cellH/scale*.16f)*labelScale,WidgetColors.resolve(config,selected[i].optString("id"))?:muted,right=x+(w-2*pad)/cols-10*scale)}}
            "horizontal_bars"->{val count=min(selected.size,if(config.optString("format")=="LARGE")6 else 4);for(i in 0 until count){val b=selected[i];val rowH=contentH/count;val y=top+i*rowH;val r=ratio(b);text(b.optString("label"),pad,y+rowH*.3f,min(12f,rowH*.29f/scale)*labelScale,WidgetColors.resolve(config,b.optString("id"))?:fg,right=w*.60f,cap=8);text(value(b),w*.65f,y+rowH*.3f,min(17f,rowH*.33f/scale)*numberScale,color=color(i,b),bold=true,cap=12,align=Paint.Align.RIGHT);val rect=RectF(pad,y+rowH*(if(count<=3).58f else .62f),w-pad,y+rowH*(if(count<=3).86f else .82f));val center=rect.centerY();val thickness=rect.height()*WidgetTuning.stroke(config,count);rect.top=max(y+rowH*.52f,center-thickness/2);rect.bottom=min(y+rowH*(if(count<=3).90f else .82f),center+thickness/2);p.color=track;c.drawRoundRect(rect,7f,7f,p);if(r.isFinite()){val bands=if(config.optBoolean("bands")&&WidgetColors.resolve(config,b.optString("id"))==null)b.optJSONArray("bands")else null;if(bands!=null&&bands.length()>0){val minValue=b.optDouble("min",0.0);val maxValue=b.optDouble("max");var previous=minValue;for(j in 0 until bands.length()){val band=bands.getJSONObject(j);val until=band.optDouble("until");val start=((previous-minValue)/(maxValue-minValue)).toFloat();val end=min(r,((until-minValue)/(maxValue-minValue)).toFloat());if(end>start){p.color=toneColor(band.optString("tone"));c.drawRect(pad+(w-2*pad)*start,rect.top,pad+(w-2*pad)*end,rect.bottom,p)};previous=until}}else{p.color=color(i,b);rect.right=pad+(w-2*pad)*r;c.drawRoundRect(rect,7f,7f,p)}}}}
            "vertical_bars"->{val count=min(selected.size,6);val compatible=selected.take(count).all{it.optString("unit")==selected[0].optString("unit")&&it.optDouble("min",0.0)==selected[0].optDouble("min",0.0)&&it.optDouble("max")==selected[0].optDouble("max")};if(!compatible)text("内容范围不同，请重新配置",pad,top+40*scale,14f,muted)else for(i in 0 until count){val b=selected[i];val r=ratio(b);val cell=(w-2*pad)/count;val x=pad+i*cell;p.color=color(i,b);if(r.isFinite())c.drawRoundRect(RectF(x+cell*(.5f-(if(count<=3).4f else .35f)*WidgetTuning.stroke(config,count).coerceAtMost(1.1f)),bottom-25*scale-(contentH-55*scale)*r,x+cell*(.5f+(if(count<=3).4f else .35f)*WidgetTuning.stroke(config,count).coerceAtMost(1.1f)),bottom-25*scale),5f,5f,p);text(value(b),x,bottom-30*scale-(contentH-55*scale)*(if(r.isFinite())r else 0f),10f*numberScale,color(i,b),right=x+cell-4*scale,cap=8,align=Paint.Align.CENTER);text(b.optString("label"),x,bottom-5*scale,10f*labelScale,WidgetColors.resolve(config,b.optString("id"))?:fg,right=x+cell-4*scale,cap=64,align=Paint.Align.CENTER)}}
            "single_ring","nested_rings"->{val count=if(type=="single_ring")1 else min(selected.size,3);val legend=count>1&&config.optString("format") in listOf("WIDE","LARGE");val radius=min(if(legend)(w*.64f-pad)/2.1f else (w-2*pad)*.45f,contentH*.44f);val x=if(legend)pad+radius else w/2f;val y=top+contentH/2;val stroke=radius*.18f*WidgetTuning.stroke(config,count);for(i in 0 until count){val b=selected[i];arc(x,y,radius-i*stroke*1.65f,ratio(b),color(i,b),stroke,b);if(legend){text(b.optString("label"),w*.67f,top+(i+.22f)*contentH/count,11f*labelScale,WidgetColors.resolve(config,b.optString("id"))?:muted);text(value(b),w*.67f,top+(i+.82f)*contentH/count,min(23f,contentH/count*.29f/scale)*numberScale,color=color(i,b),bold=true,cap=12)}};if(count==1){ringContent(selected[0],x,y,radius,stroke,30f,11f)}else {val inner=radius-(count-1)*stroke*1.65f-stroke*.5f;val half=(inner-4*scale).coerceAtLeast(0f)*.85f;text(config.optString("title").ifBlank{row.optString("title","状态")},x-half,y+4*scale,min(12f,inner*.5f/scale)*labelScale,right=x+half,cap=64,align=Paint.Align.CENTER)}}
            "ring_grid"->{val count=min(selected.size,4);for(i in 0 until count){val cellW=(w-2*pad)/2;val cellH=contentH/(if(count>2)2 else 1);val x=pad+cellW*(i%2+.5f);val y=top+cellH*(i/2+.5f);val radius=min(cellW,cellH)*.38f;val stroke=radius*(if(count<=3).21f else .16f)*WidgetTuning.stroke(config,count);arc(x,y,radius,ratio(selected[i]),color(i,selected[i]),stroke,selected[i]);ringContent(selected[i],x,y,radius,stroke,19f,9f)}}
            "segmented_ring"->{val b=selected.first();val items=b.optJSONArray("items");val total=b.optDouble("total");val radius=min(w*.26f,contentH*.4f);val x=pad+radius;val y=top+contentH/2;p.style=Paint.Style.STROKE;p.strokeWidth=radius*(if((items?.length()?:0)<=3).26f else .2f)*WidgetTuning.stroke(config,1);p.strokeCap=Paint.Cap.BUTT;p.color=track;c.drawCircle(x,y,radius,p);var start=-90f;if(items!=null&&total>0)for(i in 0 until min(items.length(),6)){val item=items.getJSONObject(i);val angle=(item.optDouble("value")/total*360).toFloat();p.color=WidgetColors.resolve(config,b.optString("id"))?.let{base->android.graphics.Color.rgb((Color.red(base)+(255-Color.red(base))*i*.09).toInt().coerceIn(0,255),(Color.green(base)+(255-Color.green(base))*i*.09).toInt().coerceIn(0,255),(Color.blue(base)+(255-Color.blue(base))*i*.09).toInt().coerceIn(0,255))}?:palette[i%4];c.drawArc(RectF(x-radius,y-radius,x+radius,y+radius),start,angle,false,p);start+=angle};p.style=Paint.Style.FILL;text(value(JSONObject(b.toString()).put("value",total)),x-radius*.6f,y,18f*numberScale,color=color(0,b),bold=true,right=x+radius*.6f,cap=10,align=Paint.Align.CENTER);if(items!=null)for(i in 0 until min(items.length(),6))text(WidgetTextLimits.cap(items.getJSONObject(i).optString("label"),4)+" "+WidgetTextLimits.number(items.getJSONObject(i).optDouble("value")),w*.6f,top+(i+1)*contentH/(items.length()+1),12f*labelScale,WidgetColors.resolve(config,b.optString("id"))?:fg)}
            else->text("请选择模板",pad,top+30*scale,16f)
        }
        if(stale)text("数据已过期 · 最后采样 "+java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(row.optLong("observed_at_ms"))),pad,h-pad,11f,muted,cap=24)
        else if(config.optBoolean("_offline"))text("网络未连通 · 最后结果",pad,h-pad,11f,muted,cap=24)
        else if(!compactRing&&config.optBoolean("updated",true))text("更新 "+java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(row.optLong("accepted_at_ms"))),pad,h-pad,11f,muted,cap=24)
        return bitmap
    }
}
