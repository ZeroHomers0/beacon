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
internal fun AdminTopicsPage(state:AdminUiState,model:ServerAdminViewModel,scroll:LazyListState,modifier:Modifier,padding:PaddingValues=PaddingValues(start=BeaconUiMetrics.PageInset,end=BeaconUiMetrics.PageInset,top=BeaconUiMetrics.PageTopInset,bottom=BeaconUiMetrics.PageBottomInset)){
    val filters=model.listFilters
    var device by filters::topicDevice
    var application by filters::topicApplication
    var status by filters::topicStatus
    var search by filters::topicSearch
    var offset by filters::topicOffset
    var reload by remember{mutableIntStateOf(0)}
    var attempted by remember{mutableStateOf("")}
    var pending by remember{mutableStateOf<JSONObject?>(null)}
    var detailOpen by remember{mutableStateOf(false)}
    var refreshing by remember{mutableStateOf(false)}
    val query=JSONObject().put("device_id",device).put("state",status).put("search",search.trim()).put("limit",50).put("offset",offset).apply{if(application!="*")put("application_id",application)}
    val request=query.toString()
    LaunchedEffect(request,state.busy,reload){
        if(!state.busy){
            val marker="$request:$reload"
            if(attempted!=marker&&(!state.topicsLoaded||state.topicsQuery!=request||reload>0)){
                kotlinx.coroutines.delay(300);attempted=marker;model.loadTopics(JSONObject(request))
            }else refreshing=false
        }
    }
    val states=listOf("all","active","stale","expired","deleted")
    val labels=listOf("全部状态","有效","数据陈旧","已过期","已清空")
    SettingsRetryFeedback("数据主题加载失败",state.refreshFailure!=null||state.topicsFailure!=null,onRetry={reload++})
    val direction=androidx.compose.ui.platform.LocalLayoutDirection.current
    Column(modifier){
        Column(Modifier.fillMaxWidth().padding(start=padding.calculateStartPadding(direction),end=padding.calculateEndPadding(direction),top=padding.calculateTopPadding(),bottom=BeaconUiMetrics.Gap8),verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap8)){
            BeaconSearchBox(search,{search=it;offset=0},"搜索主题、设备或应用",onSearch={})
            Row(horizontalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap8)){
                BeaconSummaryPill("共 ${state.topicsTotal} 个",BeaconIcons.Topics,highlighted=status=="all",enabled=!state.busy,onClick={status="all";offset=0},modifier=Modifier.weight(1f),compact=true)
                BeaconSummaryPill("本页 ${state.topics.size} 个",BeaconIcons.List,highlighted=false,enabled=false,onClick={},modifier=Modifier.weight(1f),compact=true)
            }
            if(device.isNotBlank()||application!="*"||status!="all"||search.isNotBlank())ActionFlow{
                if(device.isNotBlank())AdminFilterChip(state.devices.firstOrNull{it.id==device}?.name?:device,!state.busy){device="";offset=0}
                if(application!="*")AdminFilterChip(if(application.isBlank())"未关联应用"else state.applications.firstOrNull{it.id==application}?.name?:application,!state.busy){application="*";offset=0}
                if(status!="all")AdminFilterChip(labels.getOrElse(states.indexOf(status)){"未知"},!state.busy){status="all";offset=0}
                if(search.isNotBlank())AdminFilterChip(search,!state.busy){search="";offset=0}
            }
        }
        BeaconPullToRefresh(refreshing,{if(!state.busy){refreshing=true;reload++}},Modifier.fillMaxWidth().weight(1f)){
            BeaconSettingsList(scroll,Modifier.fillMaxSize(),contentPadding=PaddingValues(start=padding.calculateStartPadding(direction),end=padding.calculateEndPadding(direction),top=BeaconUiMetrics.Gap4,bottom=padding.calculateBottomPadding()),itemSpacing=BeaconUiMetrics.MessageCardGap){
                if(state.topics.isEmpty())item{BeaconEmptyState(BeaconIcons.List,"暂无数据主题","绑定设备后可直接上报，应用来源可选。")}
                items(state.topics,key={it.optString("device_id")+":"+it.optString("application_id")+":"+it.optString("topic_key")}){row->
                    AdminListCard(row.optString("title").ifBlank{row.optString("topic_key")},
                        "${row.optString("device_name")} · ${if(row.optString("application_id").isBlank())"未关联应用"else row.optString("source_name").ifBlank{row.optString("application_id")}} · v${row.optLong("revision")}",
                        labels.getOrElse(states.indexOf(row.optString("state"))){"未知"},!state.busy,
                        if(row.optString("state")=="deleted")emptyList()else listOf(R.string.admin_topic_clear to {model.dismissAction();pending=row}),
                        revoked=row.optString("state") in listOf("deleted","expired"),
                        onDetails={model.dismissTopic();detailOpen=true;model.loadTopic(topicIdentity(row))})
                }
                if(offset>0||state.topicsHasMore)item{BeaconActionPair(
                    dismiss={BeaconTextButton(onClick={offset=(offset-50).coerceAtLeast(0)},enabled=offset>0&&!state.busy){BeaconText("上一页")}},
                    confirm={BeaconTextButton(onClick={offset+=50},enabled=state.topicsHasMore&&!state.busy){BeaconText("下一页")}})}
            }
        }
    }
    if(detailOpen&&state.topicDetail!=null){
        val record=state.topicDetail
        BeaconAlertDialog(onDismissRequest={detailOpen=false;model.dismissTopic()},title={BeaconText(record.optString("title").ifBlank{record.optString("topic_key")})},text={
            Column(verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap8)){
                BeaconText(record.optString("body").ifBlank{"当前状态已清空或过期。"})
                SettingsHint(if(record.optBoolean("deleted"))"已清空或过期"else if(record.optLong("expires_at_ms") in 1..System.currentTimeMillis())"已过期，以下为最后记录"else if(record.optLong("stale_at_ms") in 1..System.currentTimeMillis())"数据陈旧，以下为最后记录"else"数据有效")
                SettingsHint("发送应用："+(if(record.optString("application_id").isBlank())"未关联应用"else record.optString("application_name").ifBlank{record.optString("application_id")}))
                SettingsHint("采样时间："+topicTime(record.optLong("observed_at_ms")))
                SettingsHint("更新时间："+topicTime(record.optLong("accepted_at_ms")))
                StructuredBlocksPanel(record.optJSONArray("blocks")?.toString()?:"[]")
            }
        },confirmButton={BeaconButton(onClick={detailOpen=false;model.dismissTopic()}){BeaconText("关闭")}})
    }
    pending?.let{row->BeaconConfirmDialog("清空当前状态？","将同步移除手机中的当前状态，保留版本水位。脚本继续以更高版本上报时，持续主题会重新出现。此操作不停止采集脚本。",onDismiss={if(!state.busy)pending=null},onConfirm={model.clearTopic(topicIdentity(row),query){pending=null;offset=0}},confirmText="清空",enabled=!state.busy,dismissEnabled=!state.busy,errorText=state.actionFailure?.let{stringResource(it)})}
}

private fun topicTime(time:Long)=if(time<=0)"暂无记录"else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))
