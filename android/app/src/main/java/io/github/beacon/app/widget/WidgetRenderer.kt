package io.github.beacon.app.widget

import android.graphics.*
import org.json.JSONObject
import io.github.beacon.app.data.remote.topicSnapshot
import io.github.beacon.app.domain.model.DataStatus
import kotlin.math.*

/** Shared by native desktop RemoteViews and the configuration preview. No network assets. */
object WidgetRenderer {
    private const val CORNER_RADIUS_DP = 27f
    val templates = listOf("numbers", "horizontal_bars", "vertical_bars", "single_ring", "nested_rings", "ring_grid", "segmented_ring", "text_list", "text", "sparkline", "countdown", "overview_bars", "overview_rings", "overview_list")
    val names = listOf("纯数字", "横向进度条", "纵向柱状图", "单环", "嵌套环", "独立小环", "占比圆环", "文字列表", "整段文字", "迷你趋势图", "倒计时", "数字与横条", "环形与数值", "数值与榜单")
    fun render(row: JSONObject?, inputConfig: JSONObject, width: Int, height: Int, dark: Boolean, density: Float = 1f, maxPixels: Int = 2097152): Bitmap {
        val config=WidgetConfigRules.resolved(row,inputConfig)
        val w=width.coerceAtLeast(1);val h=height.coerceAtLeast(1)
        val factor=min(density.takeIf{it.isFinite()&&it>0}?.toDouble()?:1.0,sqrt(maxPixels.coerceAtLeast(1).toDouble()/(w.toDouble()*h)))
        val bitmap=Bitmap.createBitmap((w*factor).toInt().coerceAtLeast(1),(h*factor).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        bitmap.density=Bitmap.DENSITY_NONE
        val c=Canvas(bitmap);c.scale(bitmap.width.toFloat()/w,bitmap.height.toFloat()/h)
        val scale=WidgetGeometry.scale(w,h);val pad=WidgetGeometry.inset(w,h)
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

        fun placeholder(message:String){
            val top=pad+26*scale
            val availableHeight=(h-pad-top).toInt().coerceAtLeast(1)
            val layout=WidgetPlaceholder.layout(message,(w-2*pad).toInt(),availableHeight,14f*scale,muted)
            c.save();c.translate(pad,top+((availableHeight-layout.height)/2f).coerceAtLeast(0f))
            layout.draw(c);c.restore()
        }

        p.color=if(config.optString("background")=="transparent") Color.TRANSPARENT else if(dark)Color.rgb(25,28,34)else Color.rgb(248,249,250)
        val cornerRadius=min(CORNER_RADIUS_DP,min(w,h)*.5f)
        c.drawRoundRect(RectF(0f,0f,w.toFloat(),h.toFloat()),cornerRadius,cornerRadius,p)
        val template=config.optString("template","horizontal_bars")

        val now=System.currentTimeMillis();val dataStatus=row?.topicSnapshot()?.status(now) ?: DataStatus.MISSING;val expired=dataStatus==DataStatus.EXPIRED;val stale=dataStatus==DataStatus.STALE
        text(config.optString("title").ifBlank{row?.optString("title")?:"Beacon 数据"},pad,pad+13*scale,13f,bold=true,cap=12)
        if(row?.optBoolean("_topic_deleted")==true||row?.optBoolean("_waiting")==true){placeholder(if(row.optBoolean("_topic_deleted"))"主题已删除"else"等待数据");return bitmap}
        if(row==null||expired){placeholder(if(expired)"已过期" else if(config.optString("identity").isBlank())WidgetPlaceholder.UNBOUND else"内容已删除或尚未同步");return bitmap}
        val selected=WidgetConfigRules.selected(row,config)
        val error=WidgetConfigRules.layoutError(row,config,width,height)
        if(error!=null){text(error,pad,h*.55f,14f,color=muted);return bitmap}
        fun color(index:Int,b:JSONObject):Int=WidgetColors.resolve(config,b.optString("id")) ?: if(stale)muted else if(config.optString("color")=="semantic")when(b.optString("tone")){"critical"->if(dark)0xffff9296.toInt()else 0xffc5343a.toInt();"warning"->palette[3];else->palette[0]}else palette[index%palette.size]
        fun ratio(b:JSONObject):Float{val value=b.optDouble("value");val min=b.optDouble("min",0.0);val max=b.optDouble("max");return if(value.isFinite()&&max.isFinite()&&max>min)((value-min)/(max-min)).toFloat().coerceIn(0f,1f)else Float.NaN}
        fun label(b:JSONObject):String = config.optJSONObject("short_labels")?.optString(b.optString("id")).orEmpty().ifBlank { b.optString("label") }
        fun value(b:JSONObject):String {
            val raw=b.optDouble("value");if(!raw.isFinite())return "暂无数据";val decimals=config.optInt("decimals",-1)
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
            line(label(b),top+n*1.15f+gap+l*.85f,l,false,WidgetColors.resolve(config,b.optString("id"))?:muted)
        }

        val compactRing=(template in listOf("single_ring","nested_rings") && (height<150 || width<240&&height<230))
        val needsFooter=stale||config.optBoolean("_offline")||!compactRing&&config.optBoolean("updated",true)
        val top=pad+24*scale;val bottom=h-pad-(if(needsFooter)16*scale else 0f);val contentH=bottom-top;val type=config.optString("template","horizontal_bars")
        if(selected.isEmpty()&&type!="text"){text("暂无绑定内容",pad,h*.55f,18f,color=muted);return bitmap}
        if(type in listOf("nested_rings","segmented_ring","overview_bars","overview_rings","overview_list","countdown") &&
            WidgetConfigRules.layoutError(row,config,w,h)!=null){
            placeholder("当前内容或尺寸不适合\n请在组件设置中调整")
            return bitmap
        }
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
                    text(label(b),pad,top+12*scale,12f*labelScale,WidgetColors.resolve(config,b.optString("id"))?:muted,cap=32)
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
            "numbers"->{val count=min(selected.size,if(config.optString("format")=="LARGE")8 else 4);val cols=if(config.optString("format")=="MEDIUM"&&count<=3)count else if(count>1)2 else 1;val cellH=contentH/((count+cols-1)/cols);for(i in 0 until count){val x=pad+(i%cols)*(w-2*pad)/cols;val y=top+(i/cols)*cellH;text(value(selected[i]),x,y+cellH*.52f,min(if(count==1)48f else 30f,cellH/scale*.36f)*numberScale,color=color(i,selected[i]),bold=true,right=x+(w-2*pad)/cols-10*scale,cap=12);text(label(selected[i]),x,y+cellH*.86f,min(12f,cellH/scale*.16f)*labelScale,WidgetColors.resolve(config,selected[i].optString("id"))?:muted,right=x+(w-2*pad)/cols-10*scale)}}
            "horizontal_bars"->{val count=min(selected.size,if(config.optString("format")=="LARGE")6 else 4);for(i in 0 until count){val b=selected[i];val rowH=contentH/count;val y=top+i*rowH;val r=ratio(b);text(label(b),pad,y+rowH*.3f,min(12f,rowH*.29f/scale)*labelScale,WidgetColors.resolve(config,b.optString("id"))?:fg,right=w*.60f,cap=8);text(value(b),w*.65f,y+rowH*.3f,min(17f,rowH*.33f/scale)*numberScale,color=color(i,b),bold=true,cap=12,align=Paint.Align.RIGHT);val rect=RectF(pad,y+rowH*(if(count<=3).58f else .62f),w-pad,y+rowH*(if(count<=3).86f else .82f));val center=rect.centerY();val thickness=rect.height()*WidgetTuning.stroke(config,count);rect.top=max(y+rowH*.52f,center-thickness/2);rect.bottom=min(y+rowH*(if(count<=3).90f else .82f),center+thickness/2);p.color=track;c.drawRoundRect(rect,7f,7f,p);if(r.isFinite()){val bands=if(config.optBoolean("bands")&&WidgetColors.resolve(config,b.optString("id"))==null)b.optJSONArray("bands")else null;if(bands!=null&&bands.length()>0){val minValue=b.optDouble("min",0.0);val maxValue=b.optDouble("max");var previous=minValue;for(j in 0 until bands.length()){val band=bands.getJSONObject(j);val until=band.optDouble("until");val start=((previous-minValue)/(maxValue-minValue)).toFloat();val end=min(r,((until-minValue)/(maxValue-minValue)).toFloat());if(end>start){p.color=toneColor(band.optString("tone"));c.drawRect(pad+(w-2*pad)*start,rect.top,pad+(w-2*pad)*end,rect.bottom,p)};previous=until}}else{p.color=color(i,b);rect.right=pad+(w-2*pad)*r;c.drawRoundRect(rect,7f,7f,p)}}}}
            "vertical_bars"->{val count=min(selected.size,6);val compatible=selected.take(count).all{it.optString("unit")==selected[0].optString("unit")&&it.optDouble("min",0.0)==selected[0].optDouble("min",0.0)&&it.optDouble("max")==selected[0].optDouble("max")};if(!compatible)text("内容范围不同，请重新配置",pad,top+40*scale,14f,muted)else for(i in 0 until count){val b=selected[i];val r=ratio(b);val cell=(w-2*pad)/count;val x=pad+i*cell;p.color=color(i,b);if(r.isFinite())c.drawRoundRect(RectF(x+cell*(.5f-(if(count<=3).4f else .35f)*WidgetTuning.stroke(config,count).coerceAtMost(1.1f)),bottom-25*scale-(contentH-55*scale)*r,x+cell*(.5f+(if(count<=3).4f else .35f)*WidgetTuning.stroke(config,count).coerceAtMost(1.1f)),bottom-25*scale),5f,5f,p);text(value(b),x,bottom-30*scale-(contentH-55*scale)*(if(r.isFinite())r else 0f),10f*numberScale,color(i,b),right=x+cell-4*scale,cap=8,align=Paint.Align.CENTER);text(label(b),x,bottom-5*scale,10f*labelScale,WidgetColors.resolve(config,b.optString("id"))?:fg,right=x+cell-4*scale,cap=64,align=Paint.Align.CENTER)}}
            "single_ring"->{
                val b=selected.first();val radius=min(w-2*pad,contentH)/2.24f
                val stroke=radius*.19f*WidgetTuning.stroke(config,1)
                val x=w/2f;val y=top+contentH/2
                arc(x,y,radius,ratio(b),color(0,b),stroke,b)
                ringContent(b,x,y,radius,stroke,34f,12f)
            }
            "nested_rings"->{
                val count=min(selected.size,3)
                val plan=WidgetGeometry.nested(w,top,bottom,pad,count,labelScale,WidgetTuning.stroke(config,count))
                for(i in 0 until count){
                    val b=selected[i]
                    arc(plan.centerX,plan.centerY,plan.radius-i*plan.stroke*1.65f,ratio(b),color(i,b),plan.stroke,b)
                    val cellW=if(plan.sideLegend)plan.legendWidth else plan.legendWidth/count
                    val cellH=if(plan.sideLegend)plan.legendHeight/count else plan.legendHeight
                    val lx=plan.legendX+if(plan.sideLegend)0f else i*cellW
                    val ly=plan.legendY+if(plan.sideLegend)i*cellH else 0f
                    c.save();c.clipRect(lx,ly,lx+cellW,ly+cellH)
                    val dot=2.7f*scale
                    p.color=color(i,b);c.drawCircle(lx+dot,ly+8*scale,dot,p)
                    val position=if(i==0)"外"else if(i==count-1)"内"else"中"
                    text("$position·"+label(b),lx+dot*2+4*scale,ly+12*scale,
                        11f*labelScale,muted,right=lx+cellW-4*scale,cap=16)
                    if(plan.showValues)text(value(b),lx,ly+cellH-(if(plan.sideLegend)9f else 4f)*scale,
                        min((if(plan.sideLegend)21f else 14f)*numberScale,(cellH/scale-(if(plan.sideLegend)22f else 16f))*.75f),color(i,b),true,right=lx+cellW-4*scale,cap=16)
                    c.restore()
                }
                val hole=plan.radius-(count-1)*plan.stroke*1.65f-plan.stroke/2
                if(hole>=32f)ringContent(selected.first(),plan.centerX,plan.centerY,hole,0f,28f,11f)
            }
            "countdown"->{
                val b=selected.first();val target=b.optLong("target_at_ms",-1)
                text(label(b),pad,top+16*scale,13f*labelScale,muted,cap=32)
                text(if(stale)"等待更新"else WidgetCountdown.caption(target,now),pad,top+contentH*.59f,
                    min(36f,contentH*.32f/scale)*numberScale,color(0,b),true,cap=32)
                val targetText=if(target>=0)java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(target))else"暂无时间"
                text(targetText,pad,bottom-3*scale,12f,muted,cap=32)
            }
            "overview_bars","overview_rings","overview_list"->{
                val numeric=selected.filter{it.optString("kind") in listOf("progress","metric")}
                if(numeric.isNotEmpty()){
                    val main=numeric.first();val split=top+contentH*.38f
                    if(type=="overview_rings"){
                        p.textSize=28f*numberScale*scale;p.typeface=Typeface.create("sans-serif",Typeface.BOLD)
                        val valueWidth=numeric.drop(1).maxOfOrNull{p.measureText(value(it))}?:0f
                        p.textSize=12f*labelScale*scale;p.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
                        val labelWidth=numeric.drop(1).maxOfOrNull{p.measureText(WidgetTextLimits.cap(label(it),8))}?:0f
                        val legendWidth=max(max(valueWidth,labelWidth)+4*scale,80*scale).coerceAtMost((w-2*pad)*.46f)
                        val chartW=w-2*pad-legendWidth-12*scale
                        val r=min(chartW/2.2f,contentH/2.2f)
                        val cx=pad+chartW/2;val cy=top+contentH/2
                        val stroke=r*.2f
                        arc(cx,cy,r,ratio(main),color(0,main),stroke,main)
                        ringContent(main,cx,cy,r,stroke,32f,12f)
                        numeric.drop(1).take(2).forEachIndexed{i,b->
                            val x=pad+chartW+10*scale;val cellH=contentH/2;val y=top+i*cellH
                            text(value(b),x,y+cellH*.48f,28f*numberScale,color(i+1,b),true,cap=16)
                            text(label(b),x,y+cellH*.72f,12f*labelScale,muted,cap=24)
                        }
                    }else{
                        text(label(main),pad,top+16*scale,13f*labelScale,muted,cap=32)
                        text(value(main),pad,split-12*scale,46f*numberScale,color(0,main),true,cap=24)
                        if(type=="overview_bars")numeric.drop(1).take(2).forEachIndexed{i,b->
                            val rowH=(bottom-split)/2;val y=split+i*rowH
                            text(label(b),pad,y+18*scale,12f*labelScale,muted,right=w*.57f,cap=24)
                            text(value(b),w*.6f,y+18*scale,18f*numberScale,color(i+1,b),true,cap=16,align=Paint.Align.RIGHT)
                            val rect=RectF(pad,y+rowH*.58f,w-pad,y+rowH*.82f)
                            p.color=track;c.drawRoundRect(rect,7f,7f,p)
                            val r=ratio(b);if(r.isFinite()){rect.right=rect.left+rect.width()*r;p.color=color(i+1,b);c.drawRoundRect(rect,7f,7f,p)}
                        }else{
                            val list=selected.firstOrNull{it.optString("kind")=="list"};val items=list?.optJSONArray("items")
                            val lineH=24f*scale;val limit=min(items?.length()?:0,((bottom-split)/lineH).toInt())
                            for(i in 0 until limit)text("${i+1}. "+items!!.getJSONObject(i).optString("text"),pad,split+(i+1)*lineH-4*scale,12f*labelScale,WidgetColors.resolve(config,list?.optString("id").orEmpty())?:fg,cap=64)
                        }
                    }
                }
            }
            "ring_grid"->{val count=min(selected.size,4);for(i in 0 until count){val cellW=(w-2*pad)/2;val cellH=contentH/(if(count>2)2 else 1);val x=pad+cellW*(i%2+.5f);val y=top+cellH*(i/2+.5f);val radius=min(cellW,cellH)*.44f;val stroke=radius*(if(count<=3).21f else .16f)*WidgetTuning.stroke(config,count);arc(x,y,radius,ratio(selected[i]),color(i,selected[i]),stroke,selected[i]);ringContent(selected[i],x,y,radius,stroke,19f,9f)}}
            "segmented_ring"->{
                val b=selected.first();val items=b.optJSONArray("items");val count=min(items?.length()?:0,6)
                val total=b.optDouble("total");val side=w-2*pad>contentH*1.35f&&w>=240
                val columns=if(side)1 else min(count,3).coerceAtLeast(1)
                val rows=(count+columns-1)/columns
                val legendH=if(side)contentH else rows*24f*scale*labelScale
                val chartW=if(side)(w-2*pad)*.54f else w-2*pad
                val chartH=if(side)contentH else contentH-legendH-8*scale
                val radius=min(chartW,chartH)/2.3f;val x=pad+chartW/2;val y=top+chartH/2
                val thickness=radius*(if(count<=3).26f else .2f)*WidgetTuning.stroke(config,1)
                fun segmentColor(index:Int):Int=WidgetColors.resolve(config,b.optString("id"))?.let{base->
                    Color.rgb((Color.red(base)+(255-Color.red(base))*index*.09).toInt().coerceIn(0,255),
                        (Color.green(base)+(255-Color.green(base))*index*.09).toInt().coerceIn(0,255),
                        (Color.blue(base)+(255-Color.blue(base))*index*.09).toInt().coerceIn(0,255))
                }?:palette[index%palette.size]
                p.style=Paint.Style.STROKE;p.strokeWidth=thickness;p.strokeCap=Paint.Cap.BUTT;p.color=track
                c.drawCircle(x,y,radius,p)
                var startAngle=-90f
                if(items!=null&&total>0)for(i in 0 until count){
                    val angle=(items.getJSONObject(i).optDouble("value")/total*360).toFloat()
                    p.color=segmentColor(i);c.drawArc(RectF(x-radius,y-radius,x+radius,y+radius),startAngle,angle,false,p)
                    startAngle+=angle
                }
                p.style=Paint.Style.FILL
                val centerValue=value(JSONObject(b.toString()).put("value",total))
                p.textSize=23f*scale*numberScale;p.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD)
                val centerSize=23f*numberScale*min(1f,radius*1.3f/p.measureText(centerValue).coerceAtLeast(1f))
                text(centerValue,x-radius*.65f,y+5*scale,
                    centerSize,color(0,b),true,right=x+radius*.65f,cap=12,align=Paint.Align.CENTER)
                val legendX=if(side)pad+chartW+10*scale else pad
                val legendY=if(side)top else top+chartH+8*scale
                val cellW=(w-pad-legendX)/columns;val cellH=legendH/rows.coerceAtLeast(1)
                if(items!=null)for(i in 0 until count){
                    val lx=legendX+(i%columns)*cellW;val ly=legendY+(i/columns)*cellH
                    p.color=segmentColor(i);c.drawCircle(lx+2.5f*scale,ly+cellH*.5f-3*scale,2.5f*scale,p)
                    val item=items.getJSONObject(i)
                    val caption=WidgetTextLimits.cap(item.optString("label"),4)+" "+WidgetTextLimits.number(item.optDouble("value"))
                    text(caption,lx+8*scale,ly+cellH*.5f+2*scale,11f*labelScale,fg,right=lx+cellW-4*scale,cap=24)
                }
            }
            else->text("请选择模板",pad,top+30*scale,16f)
        }
        if(stale)text("数据较旧 · 最后采样 "+java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(row.optLong("observed_at_ms"))),pad,h-pad,11f,muted,cap=24)
        else if(config.optBoolean("_offline"))text("网络未连通 · 最后结果",pad,h-pad,11f,muted,cap=24)
        else if(!compactRing&&config.optBoolean("updated",true))text("更新约 "+java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(WidgetPerformance.updatedAt(row))),pad,h-pad,11f,muted,cap=24)
        return bitmap
    }
}
