package io.github.beacon.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.data.local.UiStyleStore
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.BeaconTheme
import io.github.beacon.app.ui.theme.LocalBeaconUiStyle
import org.json.JSONArray
import org.json.JSONObject

class StateWidgetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val style=UiStyleStore(this)
        setContent {
            BeaconTheme(style.load(),style.loadMode()) {
                StateWidgetEditor(id,intent.getBooleanExtra("detail",false),intent.getStringExtra("source"),intent.getStringExtra("identity"),intent.getStringExtra("format"),
                    onExit={finish()},onSaved={setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));finish()})
            }
        }
    }
}

private fun widgetDefaults(raw: String,row: JSONObject?): String {
    val config=runCatching{JSONObject(raw)}.getOrElse{JSONObject()}
    if(!config.has("template"))config.put("template","horizontal_bars")
    if(!config.has("color"))config.put("color","fixed")
    if(!config.has("background"))config.put("background","system")
    if(!config.has("metrics"))config.put("metrics",JSONArray(WidgetConfigRules.selected(row?:JSONObject(),config).take(4).map{it.optString("id")}))
    if(row!=null&&!config.has("source"))config.put("source",row.optString("local_source")).put("identity",row.optString("local_identity"))
    return config.toString()
}

@Composable
internal fun StateWidgetEditor(id:Int, detail:Boolean, source:String?, identity:String?, requestedFormat:String?, onExit:()->Unit, onSaved:()->Unit) {
    val context=LocalContext.current
    val cache=remember{StateCache(context)}
    DisposableEffect(cache){onDispose{cache.close()}}
    var rows by remember{mutableStateOf(cache.all())}
    val stored=remember{cache.preference("widget:$id")}
    val initial=remember {
        val saved=runCatching{JSONObject(stored)}.getOrElse{JSONObject()}
        val wantedSource=source?:saved.optString("source")
        val wantedIdentity=identity?:saved.optString("identity")
        val row=if(wantedSource.isBlank())rows.firstOrNull()else rows.firstOrNull{it.optString("local_source")==wantedSource&&it.optString("local_identity")==wantedIdentity}
        widgetDefaults(stored,row)
    }
    var draft by rememberSaveable{mutableStateOf(initial)}
    var baseline by rememberSaveable{mutableStateOf(initial)}
    val config=remember(draft){JSONObject(draft)}
    val row=rows.firstOrNull{it.optString("local_source")==config.optString("source")&&it.optString("local_identity")==config.optString("identity")}
    var editing by rememberSaveable{mutableStateOf(!detail)}
    val defaultFormat=remember{requestedFormat?:WidgetInstances.format(context,id).name}
    val formatName=config.optString("format",defaultFormat)
    val existing=id!=AppWidgetManager.INVALID_APPWIDGET_ID
    val format=if(existing)WidgetInstances.format(context,id)else runCatching{WidgetFormat.valueOf(formatName)}.getOrDefault(WidgetFormat.SQUARE)
    var previewSize by remember{mutableStateOf(WidgetInstances.previewSize(context,id,format))}
    var discard by remember{mutableStateOf(false)}
    var feedback by remember{mutableStateOf("")}
    val dirty=draft!=baseline
    fun update(change:(JSONObject)->Unit){draft=JSONObject(draft).apply(change).toString()}
    fun back(){if(editing&&dirty)discard=true else if(editing&&detail)editing=false else onExit()}
    BackHandler{back()}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,format){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME){previewSize=WidgetInstances.previewSize(context,id,format);rows=cache.all()}}
        lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(format){previewSize=WidgetInstances.previewSize(context,id,format)}
    LaunchedEffect(cache){while(true){kotlinx.coroutines.delay(2000);rows=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){cache.all()}}}
    val error=if(row==null)"暂无状态数据，请先由发送方上报。"else WidgetConfigRules.layoutError(row,config,previewSize.width.toInt(),previewSize.height.toInt())
    val save:()->Unit={
        if(error==null&&row!=null){
            try {
                val saved=JSONObject(draft).put("format",format.name)
                if(existing){
                    cache.preference("widget:$id",saved.toString());BeaconStateWidget.refresh(context,intArrayOf(id))
                    draft=saved.toString();baseline=draft
                    if(detail)editing=false else onSaved()
                }else{
                    val manager=AppWidgetManager.getInstance(context)
                    if(!manager.isRequestPinAppWidgetSupported)feedback="请从桌面的小组件列表添加。"
                    else{
                        val callback=Intent(context,PinStateWidgetReceiver::class.java).setData(android.net.Uri.parse("beacon://pin/"+java.util.UUID.randomUUID())).putExtra("config",saved.toString())
                        val pending=PendingIntent.getBroadcast(context,0,callback,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                        if(manager.requestPinAppWidget(format.component(context),null,pending))onSaved()else feedback="桌面未接受添加请求，请重试。"
                    }
                }
            }catch(_:Exception){feedback="保存失败，请重试。"}
        }
    }
    val bar=rememberBeaconBarBehavior();val style=LocalBeaconUiStyle.current
    BeaconScaffold(topBar={
        BeaconTopBar(if(editing)"小组件设置"else"状态详情",false,bar,
            navigationIcon={BeaconIconButton(onClick={back()}){Icon(BeaconIcons.Back,"返回")}},
            actions={if(editing){
                if(existing)BeaconSaveAction("保存设置",dirty||stored.isBlank(),false,error==null&&(dirty||stored.isBlank()),save)
                else BeaconActionIcon(BeaconIcons.Add,"添加到桌面",enabled=error==null,onClick=save)
            }})
    },bottomBar={}){padding->
        BeaconSettingsList(rememberLazyListState(),Modifier.fillMaxSize().padding(padding).nestedScroll(bar.connection(style)),contentPadding=PaddingValues(BeaconUiMetrics.PageInset)) {
            if(editing){
                item { WidgetPreview(row,config,previewSize,existing,format,error) }
                item { SettingsGroup("数据来源"){
                    if(!existing)BeaconDropdownPreference("组件规格",WidgetFormat.entries.map{it.label},format.ordinal,settingsRow=true,icon=BeaconIcons.Applications){index->update{it.put("format",WidgetFormat.entries[index].name)}}
                    BeaconDropdownPreference("主题",rows.map{it.optString("title")+it.optString("application_name").let{name->if(name.isBlank())""else" · $name"}},rows.indexOf(row),enabled=rows.isNotEmpty(),settingsRow=true,icon=BeaconIcons.Messages){index->
                        val selected=rows[index]
                        val next=JSONObject(draft).put("source",selected.optString("local_source")).put("identity",selected.optString("local_identity"))
                        next.remove("metrics")
                        next.put("metrics",JSONArray(WidgetConfigRules.selected(selected,next).take(4).map{it.optString("id")}))
                        draft=next.toString()
                    }
                }}
                if(row!=null){
                    item { WidgetMetricSelector(row,config){next->draft=next.toString()} }
                    item { SettingsGroup("标题") { Column(Modifier.padding(BeaconUiMetrics.CardInset)){
                        BeaconTextField(config.optString("title"),{value->update{it.put("title",if(value.codePointCount(0,value.length)>12)value.substring(0,value.offsetByCodePoints(0,12))else value)}},Modifier.fillMaxWidth(),label={BeaconText("自定义短标题")},labelText="自定义短标题",singleLine=true,placeholder="留空使用主题名称")
                        SettingsHint("最多12个字符，桌面按实际宽度省略显示。")
                    }}}
                    item { WidgetTemplateSelector(row,config,previewSize){value->update{it.put("template",value)}} }
                    item { SettingsGroup("外观") {
                        BeaconDropdownPreference("指标配色",listOf("固定配色","风险配色"),if(config.optString("color")=="semantic")1 else 0,settingsRow=true,icon=BeaconIcons.Palette){index->update{it.put("color",if(index==0)"fixed"else"semantic")}}
                        BeaconDropdownPreference("卡片背景",listOf("跟随系统","浅色","深色","透明"),listOf("system","light","dark","transparent").indexOf(config.optString("background","system")),settingsRow=true,icon=BeaconIcons.ThemeMode){index->update{it.put("background",listOf("system","light","dark","transparent")[index])}}
                    }}
                    item { SettingsGroup("显示选项") { Column(Modifier.padding(horizontal=BeaconUiMetrics.CardInset)){
                        BeaconPreferenceToggle("显示阈值色带","使用发送方提供的阈值",config.optBoolean("bands")){value->update{it.put("bands",value)}}
                        BeaconPreferenceToggle("显示更新时间","紧凑尺寸下优先保证指标可读",config.optBoolean("updated",true)){value->update{it.put("updated",value)}}
                    }}}
                }
            }else if(row!=null){
                item { WidgetStateDetail(row,id,cache,onEdit={baseline=draft;editing=true},onExit=onExit) }
            }else item { SettingsGroup("状态已失效"){Column(Modifier.padding(BeaconUiMetrics.CardInset)){SettingsHint("原有配置保留，可返回设置重新选择数据。")}} }
        }
    }
    if(feedback.isNotBlank())BeaconFailureDialog(feedback){feedback=""}
    if(discard)BeaconConfirmDialog("放弃修改？","未保存的设置将不会应用到桌面。",onDismiss={discard=false},onConfirm={discard=false;draft=baseline;if(detail)editing=false else onExit()},confirmText="放弃修改",dismissText="继续编辑",danger=false)
}
