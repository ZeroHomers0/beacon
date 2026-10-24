package io.github.beacon.app.feature.messages

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.widget.StateWidgetActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun StatesPage(modifier:Modifier=Modifier, busy:Boolean=false, connected:Boolean=true, onRefresh:()->Unit={}){
    val context=LocalContext.current;var rows by remember{mutableStateOf(emptyList<JSONObject>())};var search by remember{mutableStateOf("")};var showHidden by remember{mutableStateOf(false)}
    var version by remember{mutableIntStateOf(0)}
    io.github.beacon.app.widget.ObserveStateChanges{version++}
    io.github.beacon.app.widget.ObserveStateDeadline(rows){version++}
    LaunchedEffect(showHidden,version){rows=withContext(Dispatchers.IO){StateCache(context).use{db->db.all().filter{r->showHidden||db.preference("hidden:"+r.optString("local_source")+":"+r.optString("local_identity"))!="1"}}}}
    val visible=rows.sortedByDescending{it.optLong("accepted_at_ms")}.filter{search.isBlank()||it.optString("title").contains(search,true)||it.optString("application_name").contains(search,true)}
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing,busy) { if(refreshing&&!busy){version++;refreshing=false} }
    BeaconPullToRefresh(refreshing, { if(!busy){refreshing=true;onRefresh();version++} }, modifier, enabled=connected) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=BeaconUiMetrics.PageInset),verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.SectionGap)){
        item{BeaconSearchBox(search,{search=it},"搜索主题或应用",Modifier.fillMaxWidth(),onSearch={})}
        item{BeaconTextButton(onClick={showHidden=!showHidden}){BeaconText(if(showHidden)"隐藏已隐藏主题"else"显示已隐藏主题")}}
        item{BeaconText("共 ${visible.size} 个主题 · 数据主题不计入未读",style=MaterialTheme.typography.labelMedium)}
        if(visible.isEmpty())item{BeaconText("暂无数据主题，等待发送方上报。连接恢复后同步最新数据。")}
        items(visible,key={it.optString("local_source")+":"+it.optString("local_identity")}){row->
            BeaconCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(BeaconUiMetrics.CardInset),verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap12)){
                    BeaconText(row.optString("title"),style=MaterialTheme.typography.titleMedium)
                    if(row.optBoolean("local_imported"))BeaconText("离线快照 · 不参与实时同步")
                    BeaconText(row.optString("body"));val now=System.currentTimeMillis()
                    if(row.optLong("expires_at_ms") in 1..now)BeaconText("数据已失效")else if(row.optLong("stale_at_ms") in 1..now)BeaconText("数据已过期")
                    BeaconText(row.optString("application_name"),style=MaterialTheme.typography.labelSmall)
                    BeaconTextButton(onClick={context.startActivity(Intent(context,StateWidgetActivity::class.java).putExtra("source",row.optString("local_source")).putExtra("identity",row.optString("local_identity")).putExtra("detail",true))}){BeaconText("查看详情 / 桌面组件")}
                }
            }
        }
    }
    }
}
