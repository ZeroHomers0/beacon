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
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle){
        val observer=androidx.lifecycle.LifecycleEventObserver{_,event->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)version++
        }
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer)}
    }
    ObserveStateChanges{version++}
    ObserveStateDeadline(rows){version++}
    LaunchedEffect(version){
        val current=WidgetInstances.ids(context).toList()
        val snapshot=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){StateCache(context).use{cache->
            cache.all() to current.associateWith{id->runCatching{JSONObject(cache.preference("widget:$id"))}.getOrElse{JSONObject()}}
        }}
        ids=current;rows=snapshot.first;configs=snapshot.second
    }
    BeaconSettingsList(scroll,modifier.fillMaxSize(),contentPadding=padding){
        if(ids.isEmpty())item { SettingsGroup("暂无小组件"){
            Column(Modifier.padding(BeaconUiMetrics.CardInset)){
                SettingsHint("长按桌面空白处，在小组件列表中选择 Beacon。")
            }
        }}
        ids.forEach{id->
            item(key="widget:$id"){
                val config=configs[id]?:JSONObject()
                val row=rows.firstOrNull{it.optString("local_source")==config.optString("source")&&it.optString("local_identity")==config.optString("identity")}
                val format=WidgetInstances.format(context,id)
                Box(Modifier.testTag("widget-instance:$id")){
                val online=WidgetHealth.online(context)
                val name=config.optString("instance_name").ifBlank{config.optString("title")}.ifBlank{row?.optString("title").orEmpty()}.ifBlank{"未配置小组件"}
                SettingsGroup("${format.label} · ${format.description}"){
                    SettingsRow(name,
                        if(row==null)"选择数据主题"else WidgetHealth.description(row,online=online),
                        BeaconIcons.Applications,onClick={context.startActivity(Intent(context,StateWidgetActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id))})
                    WidgetManagerPreview(row,JSONObject(config.toString()).put("format",format.name).put("_offline",!online),WidgetInstances.previewSize(context,id,format))
                }
                }
            }
        }
        if(ids.isNotEmpty())item{SettingsHint("点击小组件名称编辑；添加或移除请在桌面操作。")}
    }
}

@Composable
private fun WidgetManagerPreview(row:JSONObject?,config:JSONObject,size:android.util.SizeF){
    val dark=when(config.optString("background","system")){"light"->false;"dark"->true;else->androidx.compose.foundation.isSystemInDarkTheme()}
    val density=androidx.compose.ui.platform.LocalDensity.current.density
    val clock=rememberWidgetClock(row,config)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(BeaconUiMetrics.CardInset),contentAlignment=androidx.compose.ui.Alignment.Center){
        val factor=minOf(1f,maxWidth.value/size.width,140f/size.height)
        val bitmap=remember(WidgetPerformance.displayKey(row,config,clock),size,density,dark,factor){WidgetRenderer.render(row,config,size.width.toInt(),size.height.toInt(),dark,density*factor)}
        androidx.compose.foundation.Image(bitmap.asImageBitmap(),"已有小组件预览",Modifier.width((size.width*factor).dp).height((size.height*factor).dp))
    }
}
