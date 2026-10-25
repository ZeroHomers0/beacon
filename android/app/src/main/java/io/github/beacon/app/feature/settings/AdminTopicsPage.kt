package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.feature.messages.StructuredBlocksPanel
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import org.json.JSONObject

internal fun topicIdentity(row:JSONObject)=JSONObject().put("device_id",row.getString("device_id")).put("application_id",row.optString("application_id")).put("topic_key",row.getString("topic_key")).put("revision",row.getLong("revision"))

@Composable
internal fun AdminTopicsPage(state:AdminUiState,model:ServerAdminViewModel,scroll:LazyListState,modifier:Modifier,padding:PaddingValues=PaddingValues(start=BeaconUiMetrics.PageInset,end=BeaconUiMetrics.PageInset,top=BeaconUiMetrics.PageTopInset,bottom=BeaconUiMetrics.PageBottomInset)){
    val context=androidx.compose.ui.platform.LocalContext.current
    val creating by model.creating.collectAsState()
    var editing by remember{mutableStateOf<JSONObject?>(null)}
    var deleting by remember{mutableStateOf<JSONObject?>(null)}
    fun copy(label:String,text:String){(context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(label,text))}
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
    var detailIdentity by remember { mutableStateOf<JSONObject?>(null) }
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
    val states=if(state.topicCatalog)listOf("all","waiting","active","stale","expired")else listOf("all","active","stale","expired","deleted")
    val labels=if(state.topicCatalog)listOf("全部状态","等待数据","有效","数据陈旧","已过期")else listOf("全部状态","有效","数据陈旧","已过期","已清空")
    SettingsRetryFeedback("数据主题加载失败",state.refreshFailure!=null||state.topicsFailure!=null,onRetry={reload++})
    SettingsRetryFeedback("详情加载失败",detailOpen&&state.topicDetailFailure!=null,onRetry={detailIdentity?.let(model::loadTopic)})
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
                if(state.topics.isEmpty())item{BeaconEmptyState(BeaconIcons.List,"暂无数据主题","先从右上角创建主题，再将上报示例交给脚本。无需创建应用。")}
                items(state.topics,key={it.optString("device_id")+":"+it.optString("application_id")+":"+it.optString("topic_key")}){row->
                    AdminListCard(row.optString("title").ifBlank{row.optString("topic_key")},
                        "${row.optString("device_name")} · ${if(row.optString("application_id").isBlank())"未关联应用"else row.optString("source_name").ifBlank{row.optString("application_id")}} · v${row.optLong("revision")}",
                        labels.getOrElse(states.indexOf(row.optString("state"))){"未知"},!state.busy,
                        buildList{
                            if(row.optLong("revision")>0&&row.optString("state")!="waiting")add(R.string.admin_topic_clear to {model.dismissAction();pending=row})
                            if(state.topicCatalog){
                                add(R.string.admin_topic_rename to {model.dismissAction();editing=row})
                                add(R.string.admin_topic_copy_id to {copy("主题标识",row.optString("topic_key"))})
                                add(R.string.admin_topic_copy_example to {copy("上报示例",topicReportExample(state.endpoint,row))})
                                add(R.string.admin_topic_delete to {model.dismissAction();deleting=row})
                            }
                        },
                        revoked=row.optString("state") in listOf("deleted","expired"),
                        onDetails=if(row.optLong("revision")>0)({model.dismissTopic();detailOpen=true;detailIdentity=topicIdentity(row);model.loadTopic(detailIdentity!!)})else null)
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
    if((creating||editing!=null)&&state.topicCatalog){
        val target=editing
        val active=state.devices.filter{!it.revoked}
        var name by rememberSaveable(target?.toString(),creating){mutableStateOf(target?.optString("name").orEmpty())}
        var deviceId by rememberSaveable(target?.toString(),creating){mutableStateOf(target?.optString("device_id")?:active.firstOrNull()?.id.orEmpty())}
        var applicationId by rememberSaveable(target?.toString(),creating){mutableStateOf(target?.optString("application_id").orEmpty())}
        var key by rememberSaveable(target?.toString(),creating){mutableStateOf(target?.optString("topic_key").orEmpty())}
        val requestId=rememberSaveable(creating){java.util.UUID.randomUUID().toString()}
        val apps=state.applications.filter{it.deviceId==deviceId&&!it.revoked}
        val validKey=key.isBlank()||key.toByteArray().size<=128&&key.none{Character.isISOControl(it)}
        fun close(){editing=null;model.dismissCreation()}
        BeaconAlertDialog(onDismissRequest={if(!state.busy)close()},title={BeaconText(if(target==null)"新建数据主题"else"修改名称")},text={
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.SectionGap)){
                BeaconTextField(name,{name=it.take(80)},Modifier.fillMaxWidth().testTag("topic-name"),label={BeaconText("主题名称")},labelText="主题名称",singleLine=true,enabled=!state.busy)
                if(target==null){
                    DevicePicker(active,deviceId,{deviceId=it;applicationId=""},!state.busy)
                    BeaconDropdownPreference("发送应用",listOf("未关联应用")+apps.map{it.name},(apps.indexOfFirst{it.id==applicationId}+1).coerceAtLeast(0),enabled=!state.busy,stacked=true){applicationId=if(it==0)""else apps[it-1].id}
                    BeaconTextField(key,{key=it.take(128)},Modifier.fillMaxWidth(),label={BeaconText("主题标识（可选）")},labelText="主题标识（可选）",singleLine=true,enabled=!state.busy,placeholder="留空自动生成")
                    if(!validKey)SettingsHint("标识不能包含控制字符，UTF-8 编码最多128字节。")
                    SettingsHint("先创建主题，脚本仅更新已创建的主题。未关联应用时使用设备推送密钥。")
                }else{
                    SettingsHint("主题标识：$key")
                    SettingsHint("修改名称不会改变脚本或小组件绑定。")
                }
                state.actionFailure?.let{SettingsHint(stringResource(it))}
            }
        },confirmButton={BeaconButton(onClick={
            val payload=JSONObject().put("device_id",deviceId).put("application_id",applicationId).put("topic_key",key).put("name",name.trim())
            if(target==null)payload.put("request_id",requestId)else payload.put("config_version",target.getLong("config_version"))
            model.mutateTopic(if(target==null)"create"else"update",payload,query){close()}
        },enabled=!state.busy&&name.isNotBlank()&&deviceId.isNotBlank()&&validKey&&(target==null||name.trim()!=target.optString("name"))){BeaconText(if(target==null)"创建"else"保存")}},dismissButton={BeaconTextButton(onClick={close()},enabled=!state.busy){BeaconText("取消")}})
    }
    deleting?.let{row->BeaconConfirmDialog("删除主题？","主题和当前数据将直接删除，桌面组件将显示内容不可用。脚本不能重新创建主题。需要使用时请手动新建。",onDismiss={if(!state.busy)deleting=null},onConfirm={
        val payload=JSONObject().put("device_id",row.getString("device_id")).put("application_id",row.optString("application_id")).put("topic_key",row.getString("topic_key")).put("config_version",row.getLong("config_version"))
        model.mutateTopic("delete",payload,query){deleting=null;offset=0}
    },confirmText="删除",enabled=!state.busy,dismissEnabled=!state.busy,errorText=state.actionFailure?.let{stringResource(it)})}

    pending?.let{row->BeaconConfirmDialog("清空当前状态？","将同步移除手机中的当前状态，保留版本水位。脚本继续以更高版本上报时，持续主题会重新出现。此操作不停止采集脚本。",onDismiss={if(!state.busy)pending=null},onConfirm={model.clearTopic(topicIdentity(row),query){pending=null;offset=0}},confirmText="清空",enabled=!state.busy,dismissEnabled=!state.busy,errorText=state.actionFailure?.let{stringResource(it)})}
}

private fun topicTime(time:Long)=if(time<=0)"暂无记录"else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))

internal fun topicReportExample(endpoint:String,row:JSONObject):String {
    val credential=if(row.optString("application_id").isBlank())"DEVICE_PUSH_KEY"else"APPLICATION_TOKEN"
    val topic=row.optString("topic_key")
    val payload=JSONObject().put("mode","state").put("schema_version",1).put("topic_key",topic).put("revision",1).put("title",row.optString("title").ifBlank{topic}).put("body","数据更新").put("blocks",org.json.JSONArray().put(JSONObject().put("kind","progress").put("id","progress").put("label","进度").put("value",40).put("max",100)))
    return "# 替换 $credential；首次 revision=1，后续先查询版本并递增。\n"+
        "curl '$endpoint/api/v1/states/meta?topic_key=${java.net.URLEncoder.encode(topic,"UTF-8")}' -H 'Authorization: Bearer $credential'\n"+
        "curl -X POST '$endpoint/api/v1/push' -H 'Authorization: Bearer $credential' -H 'Content-Type: application/json' --data '"+payload.toString().replace("'","'\"'\"'")+"'"
}
