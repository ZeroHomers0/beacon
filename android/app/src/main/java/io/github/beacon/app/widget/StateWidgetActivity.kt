package io.github.beacon.app.widget

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
import kotlinx.coroutines.launch
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
                    onExit={finish()},onSaved={setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id))})
            }
        }
    }
}

private fun widgetDefaults(raw: String,row: JSONObject?): String {
    val config=runCatching{JSONObject(raw)}.getOrElse{JSONObject()}
    if(!config.has("template"))config.put("template",if(row?.optBoolean("_waiting")==true)"auto"else WidgetConfigRules.recommend(row?:JSONObject()))
    if(!config.has("color"))config.put("color","fixed")
    if(!config.has("background"))config.put("background","system")
    if(!config.has("instance_name"))config.put("instance_name","")
    if(!config.has("title"))config.put("title","")
    if(!config.has("metrics")&&row?.optBoolean("_waiting")!=true)config.put("metrics",JSONArray(WidgetConfigRules.defaults(row?:JSONObject(),config).map{it.optString("id")}))
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
    var hasSaved by rememberSaveable{mutableStateOf(stored.isNotBlank())}
    val config=remember(draft,requestedFormat){JSONObject(draft).apply{if(!has("format")&&requestedFormat!=null)put("format",requestedFormat)}}
    val row=rows.firstOrNull{it.optString("local_source")==config.optString("source")&&it.optString("local_identity")==config.optString("identity")}
    var sourceFilter by rememberSaveable{mutableStateOf("*")}
    val sourceIds=listOf("*")+rows.map{it.optString("application_id")}.distinct()
    LaunchedEffect(sourceIds){if(sourceFilter !in sourceIds)sourceFilter="*"}
    val sourceNames=sourceIds.map{key->if(key=="*")"全部应用"else if(key.isBlank())"未关联应用"else rows.firstOrNull{it.optString("application_id")==key}?.optString("application_name").orEmpty().ifBlank{key}}
    val choices=(rows.filter{!it.optBoolean("_topic_deleted")}.filter{sourceFilter=="*"||it.optString("application_id")==sourceFilter}+listOfNotNull(row)).distinctBy{it.optString("local_source")+":"+it.optString("local_identity")}
    var editing by rememberSaveable{mutableStateOf(!detail)}
    val defaultFormat=remember{requestedFormat?:WidgetInstances.format(context,id).name}
    val formatName=config.optString("format",defaultFormat)
    val existing=id!=AppWidgetManager.INVALID_APPWIDGET_ID
    val format=if(existing)WidgetInstances.format(context,id)else runCatching{WidgetFormat.valueOf(formatName)}.getOrDefault(WidgetFormat.SQUARE)
    var previewSize by remember{mutableStateOf(WidgetInstances.previewSize(context,id,format))}
    var discard by remember{mutableStateOf(false)}
    var feedback by remember{mutableStateOf("")}
    var invalidation by remember{mutableIntStateOf(0)}
    var topicSyncKey by remember { mutableIntStateOf(0) }
    var topicSyncing by remember { mutableStateOf(false) }
    var topicSyncMessage by remember { mutableStateOf("") }
    val editorLifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(editorLifecycle,editing,topicSyncKey) {
        var sync:WidgetTopicSync?=null
        fun start(){
            if(!editing||sync!=null)return
            topicSyncing=true;topicSyncMessage="正在同步可选主题…"
            sync=WidgetTopicSync(context){message->topicSyncing=false;topicSyncMessage=message;invalidation++}.also{it.start()}
        }
        fun stop(){sync?.close();sync=null;topicSyncing=false}
        val observer=LifecycleEventObserver{_,event->when(event){Lifecycle.Event.ON_START->start();Lifecycle.Event.ON_STOP->stop();else->Unit}}
        editorLifecycle.addObserver(observer)
        if(editorLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))start()
        onDispose{editorLifecycle.removeObserver(observer);stop()}
    }
    ObserveStateChanges{invalidation++}
    ObserveStateDeadline(rows){invalidation++}
    LaunchedEffect(invalidation){
        rows=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){StateCache(context).use{it.all()}}

    }
    val dirty=draft!=baseline||!hasSaved
    fun update(change:(JSONObject)->Unit){draft=JSONObject(draft).apply(change).toString()}
    fun back(){if(editing&&dirty)discard=true else if(editing&&detail)editing=false else onExit()}
    BackHandler{back()}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,format){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME){previewSize=WidgetInstances.previewSize(context,id,format);rows=cache.all()}}
        lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(format){previewSize=WidgetInstances.previewSize(context,id,format)}
    val error=if(row==null)"请先选择一个已创建的数据主题。"else WidgetConfigRules.layoutError(row,config,previewSize.width.toInt(),previewSize.height.toInt())
    val save:()->Unit={
        if(error==null&&row!=null){
            try {
                val saved=JSONObject(draft).put("format",format.name)
                if(existing){
                    cache.preference("widget:$id",saved.toString());BeaconStateWidget.refresh(context,intArrayOf(id))
                    draft=saved.toString();baseline=draft;hasSaved=true
                    onSaved()
                }else{
                    feedback="请长按桌面空白处，打开小组件列表并选择 Beacon。App 仅管理已添加的组件。"
                }
            }catch(_:Exception){feedback="保存失败，请重试。"}
        }
    }
    val scroll=rememberLazyListState();val scope=rememberCoroutineScope()
    val bar=rememberBeaconBarBehavior();val style=LocalBeaconUiStyle.current
    BeaconScaffold(topBar={
        BeaconTopBar(if(editing)"小组件设置"else"内容详情",false,bar,
            navigationIcon={BeaconIconButton(onClick={back()}){Icon(BeaconIcons.Back,"返回")}},
            actions={if(editing){
                BeaconIconButton(onClick={scope.launch{scroll.animateScrollToItem(0)}}){Icon(BeaconIcons.Info,"查看预览")}
                if(existing)BeaconSaveAction("保存设置",dirty,false,error==null&&dirty,save)
                else BeaconActionIcon(BeaconIcons.Info,"桌面添加说明",onClick={feedback="请长按桌面空白处，从小组件列表选择 Beacon。"})
            }})
    },bottomBar={}){padding->
        BeaconSettingsList(scroll,Modifier.fillMaxSize().padding(padding).nestedScroll(bar.connection(style)),contentPadding=PaddingValues(start=BeaconUiMetrics.PageInset,end=BeaconUiMetrics.PageInset,top=BeaconUiMetrics.PageTopInset,bottom=BeaconUiMetrics.PageBottomInset)) {
            if(editing){
                item { WidgetPreview(row,JSONObject(config.toString()).put("format",format.name).put("_offline",!WidgetHealth.online(context)),previewSize,existing,format,error) }
                item { SettingsGroup("数据选择"){
                    if(!existing)BeaconDropdownPreference("组件规格",WidgetFormat.entries.map{it.label},format.ordinal,settingsRow=true,icon=BeaconIcons.Applications){index->update{it.put("format",WidgetFormat.entries[index].name)}}
                    BeaconDropdownPreference("按发送应用筛选",sourceNames,sourceIds.indexOf(sourceFilter).coerceAtLeast(0),settingsRow=true,icon=BeaconIcons.Applications){index->sourceFilter=sourceIds[index]}
                    BeaconDropdownPreference("选择展示内容",choices.map{it.optString("title").ifBlank{it.optString("topic_key")}+" · "+(if(it.optString("application_id").isBlank())"未关联应用"else it.optString("application_name").ifBlank{it.optString("application_id")})},choices.indexOf(row),enabled=choices.isNotEmpty(),settingsRow=true,icon=BeaconIcons.Topics){index->
                        val selected=choices[index]
                        val next=JSONObject(draft).put("source",selected.optString("local_source")).put("identity",selected.optString("local_identity"))
                        next.remove("metrics")
                        next.put("template",if(selected.optBoolean("_waiting"))"auto"else WidgetConfigRules.recommend(selected))
                        if(!selected.optBoolean("_waiting"))next.put("metrics",JSONArray(WidgetConfigRules.defaults(selected,next).map{it.optString("id")}))
                        draft=next.toString()
                    }
                    SettingsCommandRow("同步主题",topicSyncMessage,BeaconIcons.Sync,enabled=!topicSyncing){topicSyncKey++}
                    Column(Modifier.padding(horizontal=BeaconUiMetrics.CardInset,vertical=BeaconUiMetrics.Gap8),verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap8)){
                        SettingsHint("仅显示当前绑定设备的主题；为其他设备创建的主题不会出现在这里。")
                        SettingsHint("选项来自服务器已创建的主题，尚未上报也可绑定。筛选仅帮助查找，选择展示内容后才会更换当前绑定。")
                        SettingsHint("未关联应用：直接使用设备推送密钥上报，无需创建应用。")
                        if(row!=null&&sourceFilter!="*"&&row.optString("application_id")!=sourceFilter)
                            SettingsHint("当前展示内容不属于筛选的应用，已保留；选择新内容后才会更换。")
                    }
                }}
                if(row!=null){
                    if(!row.optBoolean("_waiting"))item { WidgetMetricSelector(row,WidgetConfigRules.resolved(row,config)){next->draft=next.toString()} }
                    item { SettingsGroup("组件名称与标题") { Column(Modifier.padding(BeaconUiMetrics.CardInset)){
                        BeaconTextField(config.optString("instance_name"),{value->update{it.put("instance_name",WidgetTextLimits.cap(value,24))}},Modifier.fillMaxWidth(),label={BeaconText("组件名称")},labelText="组件名称",singleLine=true,placeholder="仅用于区分桌面实例")
                        BeaconTextField(config.optString("title"),{value->update{it.put("title",if(value.codePointCount(0,value.length)>12)value.substring(0,value.offsetByCodePoints(0,12))else value)}},Modifier.fillMaxWidth(),label={BeaconText("自定义短标题")},labelText="自定义短标题",singleLine=true,placeholder="留空使用主题名称")
                        SettingsHint("最多12个字符，桌面按实际宽度省略显示。")
                    }}}
                    if(row.optBoolean("_waiting"))item{SettingsGroup("展示样式"){Column(Modifier.padding(BeaconUiMetrics.CardInset)){SettingsHint("收到数据后自动匹配。首次上报前可设置名称与背景。")}}}
                    else item { WidgetTemplateSelector(row,WidgetConfigRules.resolved(row,config),previewSize){value->update{val suggested=WidgetConfigRules.resolved(row,it);it.put("template",value);if(!it.has("metrics"))it.put("metrics",suggested.optJSONArray("metrics")?:JSONArray(WidgetConfigRules.defaults(row,it).map{block->block.optString("id")}))}} }
                    if(!row.optBoolean("_waiting"))item { WidgetListSettings(WidgetConfigRules.resolved(row,config)){next->draft=next.toString()} }
                    if(!row.optBoolean("_waiting"))item { WidgetTuningSettings(WidgetConfigRules.resolved(row,config)){next->draft=next.toString()} }
                    item { WidgetSimpleAppearance(row,config){next->draft=next.toString()} }
                    item { WidgetMoreSettings(row,config){next->draft=next.toString()} }
                    item { SettingsGroup("刷新与状态"){
                        BeaconDropdownPreference("刷新策略",listOf("及时更新（约5秒）","均衡（任务约5秒，其他约15秒）","省电（约1分钟）"),listOf("fast","balanced","economy").indexOf(config.optString("refresh","balanced")).coerceAtLeast(0),settingsRow=true,icon=BeaconIcons.Sync){index->update{it.put("refresh",listOf("fast","balanced","economy")[index])}}
                        Column(Modifier.padding(horizontal=BeaconUiMetrics.CardInset)){
                            BeaconPreferenceToggle("显示更新时间","紧凑尺寸下优先保证内容可读",config.optBoolean("updated",true)){value->update{it.put("updated",value)}}
                        }
                    }}

                }
            }else if(row!=null){
                item { WidgetStateDetail(row,id,cache,onEdit={if(existing){baseline=draft;editing=true}else feedback="请长按桌面空白处，从小组件列表选择 Beacon；添加后可在管理页面编辑。"},onExit=onExit) }
            }else item { SettingsGroup("内容不可用"){Column(Modifier.padding(BeaconUiMetrics.CardInset)){SettingsHint("原有配置保留，可返回设置重新选择数据。")}} }
        }
    }
    if(feedback.isNotBlank())BeaconAlertDialog(onDismissRequest={feedback=""},title={BeaconText("小组件")},text={BeaconText(feedback)},confirmButton={BeaconButton(onClick={feedback=""}){BeaconText("知道了")}})
    if(discard)BeaconConfirmDialog("放弃修改？","未保存的设置将不会应用到桌面。",onDismiss={discard=false},onConfirm={discard=false;draft=baseline;if(detail)editing=false else onExit()},confirmText="放弃修改",dismissText="继续编辑",danger=false)
}
