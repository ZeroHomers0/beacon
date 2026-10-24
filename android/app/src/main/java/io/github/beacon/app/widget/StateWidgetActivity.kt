package io.github.beacon.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import io.github.beacon.app.domain.model.BeaconUiStyle
import org.json.JSONArray
import org.json.JSONObject

class StateWidgetActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setResult(RESULT_CANCELED)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val style=io.github.beacon.app.data.local.UiStyleStore(this)
        setContent{BeaconTheme(style.load(),style.loadMode()){Surface{StateEditor(id,intent.getBooleanExtra("detail",false),intent.getStringExtra("source"),intent.getStringExtra("identity")){setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));finish()}}}}
    }
}

@Composable
private fun StateEditor(id:Int,detail:Boolean,source:String?,identity:String?,done:()->Unit){
    val context=androidx.compose.ui.platform.LocalContext.current
    val cache=remember{StateCache(context)};DisposableEffect(Unit){onDispose{cache.close()}}
    var rows by remember{mutableStateOf(cache.all())};val original=remember{runCatching{JSONObject(cache.preference("widget:$id"))}.getOrElse{JSONObject()}}
    var selected by remember{mutableIntStateOf(rows.indexOfFirst{it.optString("local_source")== (source?:original.optString("source"))&&it.optString("local_identity")== (identity?:original.optString("identity"))}.let{if(it<0&&source==null&&original.optString("source").isEmpty())0 else it})}
    LaunchedEffect(Unit){while(true){kotlinx.coroutines.delay(2000);val old=rows.getOrNull(selected);val fresh=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){cache.all()};if(old!=null){selected=fresh.indexOfFirst{it.optString("local_source")==old.optString("local_source")&&it.optString("local_identity")==old.optString("local_identity")}};rows=fresh}}
    var config by remember{mutableStateOf(original)};var editing by remember{mutableStateOf(!detail)}
    var backupFeedback by remember{mutableStateOf("")}
    var presets by remember{mutableStateOf(emptyList<JSONObject>())}
    val exporter=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null){backupFeedback=runCatching{val ids=AppWidgetManager.getInstance(context).getAppWidgetIds(android.content.ComponentName(context,BeaconStateWidget::class.java));val text=StatePreferencesBackup.export(cache,ids);requireNotNull(context.contentResolver.openOutputStream(uri)).use{it.write(text.toByteArray())};"模板已导出，不含消息和桌面实例 ID"}.getOrElse{"导出失败："+(it.message?:"无法写入文件")}}}
    val importer=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()){uri->if(uri!=null){backupFeedback=runCatching{val bytes=requireNotNull(context.contentResolver.openInputStream(uri)).use{io.github.beacon.app.data.local.ArchiveCrypto.readBounded(it,StatePreferencesBackup.MAX_BYTES)};require(bytes.size<=StatePreferencesBackup.MAX_BYTES);presets=StatePreferencesBackup.parse(bytes.toString(Charsets.UTF_8));"已加载模板，选择后重新添加桌面实例"}.getOrElse{"导入失败："+(it.message?:"无效文件")}}}
    val exportData=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)backupFeedback=runCatching{val raw=StateDataArchive.export(cache);requireNotNull(context.contentResolver.openOutputStream(uri)).use{it.write(raw.toByteArray())};"状态快照已导出，包含消息正文，请妥善保存"}.getOrElse{"导出失败"}}
    val importData=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()){uri->if(uri!=null)backupFeedback=runCatching{val raw=requireNotNull(context.contentResolver.openInputStream(uri)).use{io.github.beacon.app.data.local.ArchiveCrypto.readBounded(it,StateDataArchive.MAX_BYTES)}.toString(Charsets.UTF_8);StateDataArchive.restore(cache,raw);rows=cache.all();"已导入离线快照，不上传且不覆盖实时数据"}.getOrElse{"快照无效，未完成导入"}}
    val row=rows.getOrNull(selected)
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(BeaconUiMetrics.PageInset),verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.SectionGap)){
        BeaconText(if(editing)"桌面组件配置"else row?.optString("title")?:"状态已失效",style=MaterialTheme.typography.headlineSmall)
        if(row==null){BeaconText("暂无状态数据，请先由发送方上报。");BeaconTextButton(onClick=done){BeaconText("返回")};return@Column}
        if(!editing){
            BeaconText(row.optString("body"));io.github.beacon.app.feature.messages.StructuredBlocksPanel(row.optJSONArray("blocks")?.toString()?:"[]")
            io.github.beacon.app.feature.messages.MessageActionsPanel(io.github.beacon.app.data.remote.MessageActionsCodec.decode(row), "")
            BeaconTextButton(onClick={copyToClipboard(context,"状态摘要",row.optString("body"))}){BeaconText("复制摘要")}
            BeaconText("采样时间："+java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.optLong("observed_at_ms"))))
            val key=row.optString("local_source")+":"+row.optString("local_identity")
            var notify by remember{mutableStateOf(cache.preference("notify:$key")=="1")}
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){BeaconText("显示静默状态通知",Modifier.weight(1f));Switch(checked=notify,onCheckedChange={notify=it;cache.preference("notify:$key",if(it)"1"else"0");cache.preference("dismiss:$key","");StateNotifications.refresh(context)})}
            val notificationBlocks=row.optJSONArray("blocks")?:JSONArray()
            for(i in 0 until notificationBlocks.length()){val b=notificationBlocks.getJSONObject(i);if(b.optString("kind")=="progress")BeaconTextButton(onClick={cache.preference("primary:$key",b.optString("id"));StateNotifications.refresh(context)}){BeaconText("通知主指标："+b.optString("label",b.optString("id")))}}
            val hidden=cache.preference("hidden:$key")=="1"
            BeaconTextButton(onClick={cache.preference("hidden:$key",if(hidden)"0"else"1");done()}){BeaconText(if(hidden)"恢复显示此主题"else"在状态列表隐藏此主题")}
            BeaconTextButton(onClick={editing=true}){BeaconText(if(id==AppWidgetManager.INVALID_APPWIDGET_ID)"添加桌面组件"else"修改此组件")}
        }else{
            Image(WidgetRenderer.render(row,config,360,260,androidx.compose.foundation.isSystemInDarkTheme(),androidx.compose.ui.platform.LocalDensity.current.density).asImageBitmap(),"组件预览",Modifier.fillMaxWidth().height(260.dp))
            Row{BeaconTextButton(onClick={exporter.launch("beacon-widget-settings.json")}){BeaconText("导出模板")};BeaconTextButton(onClick={importer.launch(arrayOf("application/json"))}){BeaconText("导入模板")}}
            Row{BeaconTextButton(onClick={exportData.launch("beacon-state-snapshot.json")}){BeaconText("导出状态快照")};BeaconTextButton(onClick={importData.launch(arrayOf("application/json"))}){BeaconText("导入离线快照")}}
            if(backupFeedback.isNotEmpty())BeaconText(backupFeedback)
            presets.forEachIndexed{index,preset->BeaconTextButton(onClick={config=JSONObject(preset.toString());val match=rows.indexOfFirst{it.optString("local_source")==preset.optString("source")&&it.optString("local_identity")==preset.optString("identity")};if(match>=0)selected=match}){BeaconText("使用导入模板 ${index+1}")}}
            BeaconText("主题")
            rows.forEachIndexed{i,r->BeaconTextButton(onClick={selected=i;config=JSONObject(config.toString()).put("metrics",JSONArray())}){BeaconText((if(i==selected)"✓ "else "")+r.optString("title"))}}
            BeaconText("指标（按点击顺序排列）")
            val blocks=row.optJSONArray("blocks")?:JSONArray();val ids=config.optJSONArray("metrics")?:JSONArray()
            for(i in 0 until blocks.length()){val b=blocks.getJSONObject(i);if(b.optString("kind")!in listOf("metric","progress","segments"))continue;val key=b.optString("id");val chosen=(0 until ids.length()).map{ids.optString(it)}
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(chosen.contains(key),onCheckedChange={check->val next=if(check)chosen+key else chosen-key;config=JSONObject(config.toString()).put("metrics",JSONArray(next))});BeaconText(b.optString("label",key))}
            }
            androidx.compose.material3.OutlinedTextField(config.optString("title"),{config=JSONObject(config.toString()).put("title",it.take(40))},label={BeaconText("自定义短标题")},modifier=Modifier.fillMaxWidth())
            BeaconText("模板")
            WidgetRenderer.templates.forEachIndexed{i,t->BeaconTextButton(onClick={config=JSONObject(config.toString()).put("template",t)}){BeaconText((if(config.optString("template","horizontal_bars")==t)"✓ "else "")+WidgetRenderer.names[i])}}
            Row{BeaconTextButton(onClick={config=JSONObject(config.toString()).put("color","fixed")}){BeaconText("固定配色")};BeaconTextButton(onClick={config=JSONObject(config.toString()).put("color","semantic")}){BeaconText("风险配色")}}
            Row{listOf("system" to "跟随系统","light" to "浅色","dark" to "深色","transparent" to "透明").forEach{(value,label)->BeaconTextButton(onClick={config=JSONObject(config.toString()).put("background",value)}){BeaconText(label)}}}
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(config.optBoolean("bands",false),{config=JSONObject(config.toString()).put("bands",it)});BeaconText("显示发送方阈值色带")}
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(config.optBoolean("updated",true),{config=JSONObject(config.toString()).put("updated",it)});BeaconText("显示更新时间")}
            val configError=WidgetConfigRules.error(row,config)
            if(configError!=null)BeaconText(configError,color=MaterialTheme.colorScheme.error)
            BeaconTextButton(enabled=configError==null,onClick={
                val saved=JSONObject(config.toString()).put("source",row.optString("local_source")).put("identity",row.optString("local_identity"))
                if(id!=AppWidgetManager.INVALID_APPWIDGET_ID){cache.preference("widget:$id",saved.toString());cache.preference("pending-widget","");BeaconStateWidget.refresh(context,intArrayOf(id));done()}
                else{val manager=AppWidgetManager.getInstance(context);if(manager.isRequestPinAppWidgetSupported){val callbackIntent=Intent(context,PinStateWidgetReceiver::class.java).setData(android.net.Uri.parse("beacon://pin/"+java.util.UUID.randomUUID())).putExtra("config",saved.toString());val callback=android.app.PendingIntent.getBroadcast(context,0,callbackIntent,android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE);manager.requestPinAppWidget(android.content.ComponentName(context,BeaconStateWidget::class.java),null,callback)};done()}
            }){BeaconText("保存并添加")}
        }
        BeaconTextButton(onClick=done){BeaconText("返回")}
    }
}
