package io.github.beacon.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import org.json.JSONObject

@Composable
fun WidgetManagerPage(modifier:Modifier,scroll:LazyListState,padding:PaddingValues=PaddingValues(BeaconUiMetrics.PageInset)){
    val context=LocalContext.current
    var ids by remember{mutableStateOf(WidgetInstances.ids(context).toList())}
    var rows by remember{mutableStateOf(emptyList<JSONObject>())}
    var configs by remember{mutableStateOf(emptyMap<Int,JSONObject>())}
    var version by remember{mutableIntStateOf(0)}
    var refreshing by remember{mutableStateOf(false)}
    ObserveStateChanges{version++}
    ObserveStateDeadline(rows){version++}
    LaunchedEffect(version){
        val current=WidgetInstances.ids(context).toList()
        val snapshot=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){StateCache(context).use{cache->
            cache.all() to current.associateWith{id->runCatching{JSONObject(cache.preference("widget:$id"))}.getOrElse{JSONObject()}}
        }}
        if(refreshing) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){BeaconStateWidget.refresh(context,ids=current.toIntArray())}
        ids=current;rows=snapshot.first;configs=snapshot.second
        refreshing=false
        val focused=StateCache(context).use{it.preference("widget-focus").toIntOrNull()}
        if(focused in current){scroll.animateScrollToItem(1+current.indexOf(focused));StateCache(context).use{it.preference("widget-focus","")}}
    }
    BeaconPullToRefresh(refreshing,{if(!refreshing){refreshing=true;version++}},modifier){
    BeaconSettingsList(scroll,Modifier.fillMaxSize(),contentPadding=padding){
        item { SettingsGroup("桌面添加说明"){
            Column(Modifier.padding(BeaconUiMetrics.CardInset)){SettingsHint("请长按桌面空白处，从小组件列表选择 Beacon。这里仅管理已经添加的组件。")}
        }}
        if(ids.isEmpty())item { SettingsGroup("已有小组件"){
            Column(Modifier.padding(BeaconUiMetrics.CardInset)){SettingsHint("还没有添加小组件。请从桌面小组件列表添加。")}
        }}
        ids.forEach{id->
            item(key="widget:$id"){
                val config=configs[id]?:JSONObject()
                val row=rows.firstOrNull{it.optString("local_source")==config.optString("source")&&it.optString("local_identity")==config.optString("identity")}
                val format=WidgetInstances.format(context,id)
                Box(Modifier.testTag("widget-instance:$id")){
                SettingsGroup("${format.label} ${format.description}"){
                    SettingsRow(config.optString("instance_name").ifBlank{config.optString("title")}.ifBlank{row?.optString("title")?:"等待配置"},
                        listOf(row?.optString("application_name").orEmpty(),WidgetRenderer.names.getOrElse(WidgetRenderer.templates.indexOf(config.optString("template"))){"待选择模板"}).filter{it.isNotBlank()}.joinToString(" · "),
                        BeaconIcons.Applications,onClick={context.startActivity(Intent(context,StateWidgetActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id))})
                    Column(Modifier.padding(horizontal=BeaconUiMetrics.CardInset)){
                        SettingsHint(WidgetHealth.description(row,online=WidgetHealth.online(context)))
                        SettingsHint("发送应用："+(if(row==null)"暂无数据"else if(row.optString("application_id").isBlank())"未关联应用"else row.optString("application_name").ifBlank{row.optString("application_id")}))
                        SettingsHint("最后更新："+if(row==null||row.optLong("accepted_at_ms")==0L)"暂无"else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.optLong("accepted_at_ms"))))
                    }
                    WidgetManagerPreview(row,JSONObject(config.toString()).put("format",format.name).put("_offline",!WidgetHealth.online(context)),WidgetInstances.previewSize(context,id,format))
                }
                }
            }
        }
        item{SettingsHint("这里只显示 Beacon 的桌面实例。移除小组件请在桌面长按操作。")}
    }
    }
}

@Composable
private fun WidgetManagerPreview(row:JSONObject?,config:JSONObject,size:android.util.SizeF){
    val dark=when(config.optString("background","system")){"light"->false;"dark"->true;else->androidx.compose.foundation.isSystemInDarkTheme()}
    val density=androidx.compose.ui.platform.LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxWidth().padding(BeaconUiMetrics.CardInset),contentAlignment=androidx.compose.ui.Alignment.Center){
        val factor=minOf(1f,maxWidth.value/size.width,140f/size.height)
        val bitmap=remember(WidgetPerformance.displayKey(row,config),size,density,dark,factor){WidgetRenderer.render(row,config,size.width.toInt(),size.height.toInt(),dark,density*factor)}
        androidx.compose.foundation.Image(bitmap.asImageBitmap(),"已有小组件预览",Modifier.width((size.width*factor).dp).height((size.height*factor).dp))
    }
}
