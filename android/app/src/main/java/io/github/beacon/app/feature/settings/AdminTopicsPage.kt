package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.feature.messages.StructuredBlocksPanel
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import org.json.JSONObject

internal fun topicIdentity(row:JSONObject)=JSONObject().put("device_id",row.getString("device_id")).put("application_id",row.optString("application_id")).put("topic_key",row.getString("topic_key")).put("revision",row.getLong("revision"))

@Composable
internal fun AdminTopicsPage(state:AdminUiState,model:ServerAdminViewModel,scroll:LazyListState,modifier:Modifier){
    var device by rememberSaveable{mutableStateOf("")}
    var application by rememberSaveable{mutableStateOf("*")}
    var status by rememberSaveable{mutableStateOf("all")}
    var search by rememberSaveable{mutableStateOf("")}
    var offset by rememberSaveable{mutableIntStateOf(0)}
    var reload by remember{mutableIntStateOf(0)}
    var attempted by remember{mutableStateOf("")}
    var pending by remember{mutableStateOf<JSONObject?>(null)}
    var detailOpen by remember{mutableStateOf(false)}
    val query=JSONObject().put("device_id",device).put("state",status).put("search",search.trim()).put("limit",50).put("offset",offset).apply{if(application!="*")put("application_id",application)}
    val request=query.toString()
    LaunchedEffect(request,state.busy,reload){
        val marker="$request:$reload"
        if(!state.busy&&attempted!=marker){
            kotlinx.coroutines.delay(300);attempted=marker;model.loadTopics(JSONObject(request))
        }
    }
    val apps=state.applications.filter{device.isBlank()||it.deviceId==device}
    val sourceIds=listOf("*","")+apps.map{it.id}
    val sourceNames=listOf("全部来源","默认来源")+apps.map{app->app.name.ifBlank{app.id}}
    val states=listOf("all","active","stale","expired","deleted")
    val stateLabels=listOf("全部状态","有效","数据陈旧","已过期","已清空")
    SettingsRetryFeedback("状态主题加载失败",state.refreshFailure!=null,onRetry={reload++})
    BeaconSettingsList(scroll,modifier,contentPadding=PaddingValues(BeaconUiMetrics.PageInset)){
        item{BeaconSearchBox(search,{search=it;offset=0},"搜索主题、设备或应用",Modifier.fillMaxWidth(),onSearch={reload++})}
        item{SettingsGroup("筛选"){
            BeaconDropdownPreference("设备",listOf("全部设备")+state.devices.map{it.name.ifBlank{it.id}},(listOf("")+state.devices.map{it.id}).indexOf(device),settingsRow=true,icon=BeaconIcons.Device){index->device=(listOf("")+state.devices.map{it.id})[index];application="*";offset=0}
            BeaconDropdownPreference("来源",sourceNames,sourceIds.indexOf(application),settingsRow=true,icon=BeaconIcons.Applications){index->application=sourceIds[index];offset=0}
            BeaconDropdownPreference("状态",stateLabels,states.indexOf(status),settingsRow=true,icon=BeaconIcons.Filter){index->status=states[index];offset=0}
        }}
        item{SettingsGroup("状态主题 · 共 ${state.topicsTotal} 个"){
            SettingsRow("刷新","主题由设备推送或应用上报自动创建，无需先创建应用",BeaconIcons.Sync,iconTint=androidx.compose.material3.MaterialTheme.colorScheme.onSurface,onClick={reload++},enabled=!state.busy,command=true)
        }}
        if(state.topics.isEmpty())item{BeaconEmptyState(BeaconIcons.Messages,"暂无状态主题","绑定设备后可直接使用设备推送凭据上报；应用来源可选。")}
        items(state.topics,key={it.optString("device_id")+":"+it.optString("application_id")+":"+it.optString("topic_key")}){row->
            SettingsGroup(row.optString("title").ifBlank{row.optString("topic_key")}){
                Column(Modifier.padding(BeaconUiMetrics.CardInset),verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap8)){
                    SettingsHint("${row.optString("device_name")} · ${row.optString("source_name","默认来源")}")
                    SettingsHint("${row.optString("topic_key")} · ${stateLabels.getOrElse(states.indexOf(row.optString("state"))){"未知"}} · v${row.optLong("revision")}")
                }
                SettingsRow("查看最新数据","采样与更新信息、结构化指标",BeaconIcons.Info,iconTint=androidx.compose.material3.MaterialTheme.colorScheme.onSurface,onClick={model.dismissTopic();detailOpen=true;model.loadTopic(topicIdentity(row))},enabled=!state.busy)
                SettingsRow("清空当前状态","保留主题版本水位；后续上报可能重新出现",BeaconIcons.Delete,iconTint=androidx.compose.material3.MaterialTheme.colorScheme.error,onClick={model.dismissAction();pending=row},enabled=!state.busy&&row.optString("state")!="deleted",danger=true,command=true)
            }
        }
        item{BeaconActionPair(
            dismiss={BeaconTextButton(onClick={offset=(offset-50).coerceAtLeast(0)},enabled=offset>0&&!state.busy){BeaconText("上一页")}},
            confirm={BeaconTextButton(onClick={offset+=50},enabled=state.topicsHasMore&&!state.busy){BeaconText("下一页")}})}
    }
    if(detailOpen&&state.topicDetail!=null){
        val record=state.topicDetail
        BeaconAlertDialog(onDismissRequest={detailOpen=false;model.dismissTopic()},title={BeaconText(record.optString("title").ifBlank{record.optString("topic_key")})},text={
            Column(verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap8)){
                BeaconText(record.optString("body").ifBlank{"当前状态已清空或过期。"})
                SettingsHint(if(record.optBoolean("deleted"))"已清空或过期"else if(record.optLong("expires_at_ms") in 1..System.currentTimeMillis())"已过期，以下为最后记录"else if(record.optLong("stale_at_ms") in 1..System.currentTimeMillis())"数据陈旧，以下为最后记录"else"数据有效")
                SettingsHint("来源："+record.optString("application_name").ifBlank{if(record.optString("application_id").isBlank())"默认来源"else record.optString("application_id")})
                SettingsHint("采样时间："+topicTime(record.optLong("observed_at_ms")))
                SettingsHint("更新时间："+topicTime(record.optLong("accepted_at_ms")))
                StructuredBlocksPanel(record.optJSONArray("blocks")?.toString()?:"[]")
            }
        },confirmButton={BeaconButton(onClick={detailOpen=false;model.dismissTopic()}){BeaconText("关闭")}})
    }
    pending?.let{row->BeaconConfirmDialog("清空当前状态？","将同步移除手机中的当前状态，保留版本水位。脚本继续以更高版本上报时，持续主题会重新出现。此操作不停止采集脚本。",onDismiss={if(!state.busy)pending=null},onConfirm={model.clearTopic(topicIdentity(row),query){pending=null;offset=0}},confirmText="清空",enabled=!state.busy,dismissEnabled=!state.busy,errorText=state.actionFailure?.let{stringResource(it)})}
}

private fun topicTime(time:Long)=if(time<=0)"暂无记录"else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))
