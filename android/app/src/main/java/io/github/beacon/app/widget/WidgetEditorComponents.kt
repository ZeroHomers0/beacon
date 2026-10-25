package io.github.beacon.app.widget

import android.appwidget.AppWidgetManager
import android.util.SizeF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.feature.messages.MessageActionsPanel
import io.github.beacon.app.feature.messages.StructuredBlocksPanel
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun WidgetPreview(row:JSONObject?,config:JSONObject,size:SizeF,actual:Boolean,format:WidgetFormat,error:String?=null) {
    val dark=when(config.optString("background","system")){"light"->false;"dark"->true;else->androidx.compose.foundation.isSystemInDarkTheme()}
    val density=LocalDensity.current.density
    SettingsGroup("桌面预览") {
        Column(Modifier.fillMaxWidth().padding(BeaconUiMetrics.CardInset),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
            BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                val factor=minOf(1f,maxWidth.value/size.width,240f/size.height)
                val bitmap=remember(WidgetPerformance.displayKey(row,config),size,dark,density,factor){WidgetRenderer.render(row,config,size.width.toInt(),size.height.toInt(),dark,density*factor)}
                Box(Modifier.width((size.width*factor).dp).height((size.height*factor).dp)){
                    if(config.optString("background")=="transparent")Canvas(Modifier.fillMaxSize()){
                        val cell=12.dp.toPx()
                        for(x in 0..(this.size.width/cell).toInt())for(y in 0..(this.size.height/cell).toInt())drawRect(if((x+y)%2==0)Color.LightGray.copy(alpha=.25f)else Color.Gray.copy(alpha=.08f),Offset(x*cell,y*cell),Size(cell,cell))
                    }
                    Image(bitmap.asImageBitmap(),"组件预览",Modifier.fillMaxSize().testTag("widget-preview"))
                }
            }
            BeaconText("${format.label} · ${size.width.toInt()} × ${size.height.toInt()} dp · ${if(actual)"桌面尺寸"else"默认尺寸"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
            // The validation slot never inserts a row or pushes controls after a choice changes.
            Box(Modifier.fillMaxWidth().height(44.dp),contentAlignment=Alignment.CenterStart){
                BeaconText(error?:"等比预览，保存后应用到桌面。",style=MaterialTheme.typography.bodySmall,color=if(error!=null)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun WidgetMetricSelector(row:JSONObject,config:JSONObject,onChange:(JSONObject)->Unit){
    val blocks=row.optJSONArray("blocks")?:JSONArray()
    val available=WidgetConfigRules.available(row)
    val ids=config.optJSONArray("metrics")?:JSONArray()
    val chosen=(0 until ids.length()).map{ids.optString(it)}
    val ordered=chosen.map{key->available.firstOrNull{it.optString("id")==key}?:JSONObject().put("id",key).put("label","内容已失效") }+available.filter{it.optString("id") !in chosen}
    val latest by rememberUpdatedState(config)
    val changed by rememberUpdatedState(onChange)
    val move by rememberUpdatedState<(String,Int)->Unit>({key,delta->
        val array=latest.optJSONArray("metrics")?:JSONArray();val values=(0 until array.length()).map{array.optString(it)}.toMutableList()
        val index=values.indexOf(key);val next=index+delta
        if(index>=0&&next in values.indices){java.util.Collections.swap(values,index,next);changed(JSONObject(latest.toString()).put("metrics",JSONArray(values)))}
    })
    SettingsGroup("显示内容"){
        Column(Modifier.padding(horizontal=BeaconUiMetrics.CardInset)){
            ordered.forEach{block->
                val key=block.optString("id");val selected=key in chosen
                key(key){
                    var rowHeight by remember{mutableIntStateOf(1)}
                    var distance by remember{mutableFloatStateOf(0f)}
                    Row(Modifier.fillMaxWidth().heightIn(min=BeaconUiMetrics.FieldMinHeight).onSizeChanged{rowHeight=it.height}
                        .pointerInput(key){detectDragGesturesAfterLongPress(onDragStart={distance=0f},onDragEnd={distance=0f},onDragCancel={distance=0f}){change,drag->
                            change.consume();distance+=drag.y
                            if(kotlin.math.abs(distance)>rowHeight*.7f){move(key,if(distance>0)1 else -1);distance=0f}
                        }},verticalAlignment=Alignment.CenterVertically){
                        Row(Modifier.weight(1f).toggleable(selected,role=Role.Checkbox){check->
                            val next=if(check)chosen+key else chosen-key
                            onChange(JSONObject(config.toString()).put("metrics",JSONArray(next)))
                        },verticalAlignment=Alignment.CenterVertically){
                        BeaconCheckbox(selected,null)
                        Column(Modifier.weight(1f)){
                            BeaconText(block.optString("label",key),style=MaterialTheme.typography.bodyLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                            BeaconText(if(selected)"第 ${chosen.indexOf(key)+1} 项"else"未选择",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        }
                        BeaconIconButton(onClick={move(key,-1)},enabled=selected&&chosen.indexOf(key)>0){Icon(BeaconIcons.Arrow,"上移 ${block.optString("label")}",Modifier.rotate(270f))}
                        BeaconIconButton(onClick={move(key,1)},enabled=selected&&chosen.indexOf(key)<chosen.lastIndex){Icon(BeaconIcons.Arrow,"下移 ${block.optString("label")}",Modifier.rotate(90f))}
                    }
                }
            }
            Box(Modifier.padding(vertical=BeaconUiMetrics.Gap8)){SettingsHint("长按内容行拖动排序，也可使用上下移动按钮。")}
        }
    }
}

@Composable
internal fun WidgetTemplateSelector(row:JSONObject,config:JSONObject,size:SizeF,onSelect:(String)->Unit){
    SettingsGroup("模板样式"){
        Column(Modifier.padding(BeaconUiMetrics.CardInset),verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap8)){
            WidgetRenderer.templates.sortedBy{WidgetConfigRules.layoutError(row,JSONObject(config.toString()).put("template",it),size.width.toInt(),size.height.toInt())!=null}.chunked(2).forEach{pair->
                Row(horizontalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap8)){
                    pair.forEach{template->
                        val index=WidgetRenderer.templates.indexOf(template)
                        val problem=WidgetConfigRules.layoutError(row,JSONObject(config.toString()).put("template",template),size.width.toInt(),size.height.toInt())
                        Box(Modifier.weight(1f)){
                            BeaconFilterChip(config.optString("template")==template,{onSelect(template)},label={
                                CompositionLocalProvider(LocalButtonLabel provides false){
                                Column(Modifier.fillMaxWidth().padding(vertical=BeaconUiMetrics.Gap8),verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap4)){
                                    TemplateGlyph(template,problem==null)
                                    BeaconText(WidgetRenderer.names[index],maxLines=2)
                                    val compatible=problem==null
                                    BeaconText(if(compatible)"适合当前内容"else problem.orEmpty(),modifier=Modifier.heightIn(min=36.dp),style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                                }
                                }
                            },modifier=Modifier.fillMaxWidth(),enabled=problem==null)
                        }
                    }
                    if(pair.size==1)Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TemplateGlyph(type:String,enabled:Boolean=true){
    val ink=if(enabled)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha=.38f);val muted=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.2f)
    Canvas(Modifier.fillMaxWidth().height(32.dp)){
        val stroke=4.dp.toPx()
        when(type){
            "text","text_list"->for(i in 0..2){val y=size.height*(.18f+i*.3f);drawLine(if(i==0)ink else muted,Offset(0f,y),Offset(size.width*(if(i==2).6f else .9f),y),2.dp.toPx(),StrokeCap.Round)}
            "numbers"->for(i in 0..1){drawRoundRect(ink,Offset(size.width*i*.5f,size.height*.15f),Size(size.width*.3f,size.height*.4f),androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()));drawLine(muted,Offset(size.width*i*.5f,size.height*.85f),Offset(size.width*(i*.5f+.35f),size.height*.85f),2.dp.toPx())}
            "horizontal_bars"->for(i in 0..1){val y=size.height*(.25f+i*.5f);drawLine(muted,Offset(0f,y),Offset(size.width*.9f,y),stroke,StrokeCap.Round);drawLine(ink,Offset(0f,y),Offset(size.width*(.35f+i*.2f),y),stroke,StrokeCap.Round)}
            "vertical_bars"->for(i in 0..2)drawRect(ink,Offset(size.width*i*.3f,size.height*(.6f-i*.2f)),Size(size.width*.2f,size.height*(.4f+i*.2f)))
            else->{val count=if(type=="nested_rings")3 else if(type=="ring_grid")2 else 1
                for(i in 0 until count){val diameter=if(type=="nested_rings")size.height*(.9f-i*.22f)else size.height*.85f;val x=if(type=="ring_grid")size.width*(i*.5f+.1f)else(size.width-diameter)/2;val y=(size.height-diameter)/2
                    drawArc(muted,0f,360f,false,Offset(x,y),Size(diameter,diameter),style=Stroke(stroke))
                    drawArc(ink,-90f,180f+i*40,false,Offset(x,y),Size(diameter,diameter),style=Stroke(stroke,cap=StrokeCap.Round))
                }
            }
        }
    }
}

@Composable
internal fun WidgetStateDetail(row:JSONObject,id:Int,cache:StateCache,onEdit:()->Unit,onExit:()->Unit){
    val context=androidx.compose.ui.platform.LocalContext.current
    val key=row.optString("local_source")+":"+row.optString("local_identity")
    var notify by remember(key){mutableStateOf(cache.preference("notify:$key")=="1")}
    var hidden by remember(key){mutableStateOf(cache.preference("hidden:$key")=="1")}
    val array=row.optJSONArray("blocks")?:JSONArray()
    val progress=(0 until array.length()).map{array.getJSONObject(it)}.filter{it.optString("kind")=="progress"}
    var primary by remember(key){mutableStateOf(cache.preference("primary:$key"))}
    val now=System.currentTimeMillis()
    val status=when{row.optBoolean("_waiting")->"等待数据";row.optLong("expires_at_ms") in 1..now->"已失效";row.optLong("stale_at_ms") in 1..now->"数据已过期";else->"数据有效"}
    Column(verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.SettingsGroupGap)){
        SettingsGroup("状态概览"){
            Column(Modifier.padding(BeaconUiMetrics.CardInset),verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap8)){
                BeaconText(row.optString("title"),style=MaterialTheme.typography.titleLarge)
                SettingsHint(listOf(row.optString("application_name"),status).filter{it.isNotBlank()}.joinToString(" · "))
                BeaconText(row.optString("body"))
            }
        }
        SettingsGroup("内容详情") { Column(Modifier.padding(BeaconUiMetrics.CardInset)){
            StructuredBlocksPanel(array.toString())
            MessageActionsPanel(io.github.beacon.app.data.remote.MessageActionsCodec.decode(row),"")
        }}
        SettingsGroup("时间信息") { Column(Modifier.padding(BeaconUiMetrics.CardInset),verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap8)){
            SettingsHint("采样时间："+formatStateTime(row.optLong("observed_at_ms")))
            SettingsHint("最后同步："+formatStateTime(row.optLong("accepted_at_ms")))
        }}
        SettingsGroup("通知设置"){
            Column(Modifier.padding(horizontal=BeaconUiMetrics.CardInset)){
                BeaconPreferenceToggle("显示静默状态通知","仅更新这一主题的通知",notify){value->notify=value;cache.preference("notify:$key",if(value)"1"else"0");cache.preference("dismiss:$key","");StateNotifications.refresh(context);StateRefreshReceiver.schedule(context)}
            }
            if(progress.isNotEmpty())BeaconDropdownPreference("通知主内容",progress.map{it.optString("label",it.optString("id"))},progress.indexOfFirst{it.optString("id")==primary}.coerceAtLeast(0),settingsRow=true,icon=BeaconIcons.Tune){index->primary=progress[index].optString("id");cache.preference("primary:$key",primary);StateNotifications.refresh(context)}
        }
        SettingsGroup("主题显示"){
            Column(Modifier.padding(horizontal=BeaconUiMetrics.CardInset)){
                BeaconPreferenceToggle("在状态列表显示","隐藏不会删除数据或桌面组件",!hidden){show->hidden=!show;cache.preference("hidden:$key",if(hidden)"1"else"0")}
            }
            SettingsRow(if(id==AppWidgetManager.INVALID_APPWIDGET_ID)"桌面添加说明"else"编辑小组件","设置模板、内容和外观",BeaconIcons.Applications,onClick=onEdit)
        }
    }
}

private fun formatStateTime(time:Long)=if(time<=0)"暂无记录"else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))
