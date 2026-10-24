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
    LaunchedEffect(Unit){while(true){
        val current=WidgetInstances.ids(context).toList()
        val snapshot=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){StateCache(context).use{cache->
            cache.all() to current.associateWith{id->runCatching{JSONObject(cache.preference("widget:$id"))}.getOrElse{JSONObject()}}
        }}
        ids=current;rows=snapshot.first;configs=snapshot.second
        kotlinx.coroutines.delay(1500)
    }}
    BeaconSettingsList(scroll,modifier,contentPadding=padding){
        item { SettingsGroup("添加小组件"){
            WidgetFormat.entries.forEach{format->SettingsRow("添加 ${format.label}",format.description,BeaconIcons.Add,onClick={context.startActivity(Intent(context,StateWidgetActivity::class.java).putExtra("format",format.name))})}
        }}
        if(ids.isEmpty())item { SettingsGroup("已有小组件"){
            Column(Modifier.padding(BeaconUiMetrics.CardInset)){SettingsHint("还没有添加小组件。可以在这里添加，也可以从桌面小组件列表添加。")}
        }}
        ids.forEach{id->
            item(key="widget:$id"){
                val config=configs[id]?:JSONObject()
                val row=rows.firstOrNull{it.optString("local_source")==config.optString("source")&&it.optString("local_identity")==config.optString("identity")}
                val format=WidgetInstances.format(context,id)
                Box(Modifier.testTag("widget-instance:$id")){
                SettingsGroup("${format.label} ${format.description}"){
                    SettingsRow(config.optString("title").ifBlank{row?.optString("title")?:"等待配置"},
                        listOf(row?.optString("application_name").orEmpty(),WidgetRenderer.names.getOrElse(WidgetRenderer.templates.indexOf(config.optString("template"))){"待选择模板"}).filter{it.isNotBlank()}.joinToString(" · "),
                        BeaconIcons.Applications,onClick={context.startActivity(Intent(context,StateWidgetActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id))})
                    WidgetManagerPreview(row,JSONObject(config.toString()).put("format",format.name),WidgetInstances.previewSize(context,id,format))
                }
                }
            }
        }
        item{SettingsHint("这里只显示 Beacon 的桌面实例。移除小组件请在桌面长按操作。")}
    }
}

@Composable
private fun WidgetManagerPreview(row:JSONObject?,config:JSONObject,size:android.util.SizeF){
    val dark=when(config.optString("background","system")){"light"->false;"dark"->true;else->androidx.compose.foundation.isSystemInDarkTheme()}
    val density=androidx.compose.ui.platform.LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxWidth().padding(BeaconUiMetrics.CardInset),contentAlignment=androidx.compose.ui.Alignment.Center){
        val factor=minOf(1f,maxWidth.value/size.width,140f/size.height)
        val bitmap=remember(row?.toString(),config.toString(),size,density,dark,factor){WidgetRenderer.render(row,config,size.width.toInt(),size.height.toInt(),dark,density*factor)}
        androidx.compose.foundation.Image(bitmap.asImageBitmap(),"已有小组件预览",Modifier.width((size.width*factor).dp).height((size.height*factor).dp))
    }
}
