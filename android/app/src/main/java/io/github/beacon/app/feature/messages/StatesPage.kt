package io.github.beacon.app.feature.messages

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import io.github.beacon.app.data.repository.StateListRepository
import io.github.beacon.app.domain.model.AsyncPageState
import io.github.beacon.app.domain.policy.MessageMarkdown
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.widget.StateWidgetActivity
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
internal fun StatesFilterAction(showHidden: Boolean, onChange: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        BeaconActionIcon(BeaconIcons.Filter, "筛选数据", showBadge=showHidden) { open=true }
        BeaconDropdownMenu(open, { open=false }) {
            listOf(false to "仅显示未隐藏主题", true to "包含已隐藏主题").forEach { (value, title) ->
                BeaconDropdownMenuItem({ BeaconSelectableRow(title, showHidden==value) }) {
                    onChange(value)
                    open=false
                }
            }
        }
    }
}

@Composable
internal fun StatesPage(
    modifier: Modifier=Modifier,
    busy: Boolean=false,
    connected: Boolean=true,
    showHidden: Boolean=false,
    onShowHidden: (Boolean)->Unit={},
    onRefresh: ()->Unit={},
) {
    val context=LocalContext.current
    val repository=remember(context) { StateListRepository(context) }
    var rows by remember { mutableStateOf(emptyList<JSONObject>()) }
    var search by rememberSaveable { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    io.github.beacon.app.widget.ObserveStateChanges { version++ }
    io.github.beacon.app.widget.ObserveStateDeadline(rows) { version++ }
    LaunchedEffect(showHidden,version) {
        loading=true
        failed=false
        try {
            rows=repository.load(showHidden)
            loaded=true
            failed=false
        } catch(cancelled: CancellationException) {
            throw cancelled
        } catch(_: Exception) {
            failed=true
        } finally {
            loading=false
        }
    }
    LaunchedEffect(refreshing,busy) {
        if(refreshing&&!busy) { version++;refreshing=false }
    }
    val visible=remember(rows,search) {
        rows.sortedByDescending { it.optLong("accepted_at_ms") }.filter {
            search.isBlank() || listOf("title","topic_key","application_name").any { key -> it.optString(key).contains(search,true) }
        }
    }
    val scroll=rememberLazyListState()
    SettingsRetryFeedback("数据加载失败",failed&&loaded,onRetry={version++})
    Column(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(start=BeaconUiMetrics.PageInset,end=BeaconUiMetrics.PageInset,top=BeaconUiMetrics.PageTopInset,bottom=BeaconUiMetrics.Gap8),
            verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap8),
        ) {
            BeaconSearchBox(search,{search=it},"搜索主题或应用",Modifier.fillMaxWidth(),onSearch={})
            Row(horizontalArrangement=Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
                BeaconSummaryPill("共 ${rows.size} 个",BeaconIcons.Topics,enabled=false,modifier=Modifier.weight(1f),compact=true)
                BeaconSummaryPill("显示 ${visible.size} 个",BeaconIcons.List,enabled=false,modifier=Modifier.weight(1f),compact=true)
            }
            if(showHidden||search.isNotBlank()) ActionFlow {
                if(showHidden) BeaconActiveFilterChip(selected=true,onClick={onShowHidden(false)},label={BeaconText("包含已隐藏主题")})
                if(search.isNotBlank()) BeaconActiveFilterChip(selected=true,onClick={search=""},label={BeaconText(search,maxLines=1,overflow=TextOverflow.Ellipsis)})
            }
        }
        BeaconPullToRefresh(refreshing,{if(!busy){refreshing=true;onRefresh();version++}},Modifier.fillMaxWidth().weight(1f),enabled=connected) {
            PageStateHost(AsyncPageState.resolve(loaded,loading,failed,visible.isEmpty()),Modifier.fillMaxSize(),
                empty={Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){BeaconEmptyState(BeaconIcons.Topics,if(search.isNotBlank())"没有匹配的数据主题"else"暂无数据主题","可在服务器管理中创建主题，连接恢复后同步最新数据。")}},
                error={Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(BeaconUiMetrics.PageInset)){SettingsHint("数据加载失败，请重试。");BeaconButton(onClick={version++}){BeaconText("重试")}}},
            ) {
                BeaconSettingsList(scroll,Modifier.fillMaxSize().testTag("states-list"),
                    contentPadding=PaddingValues(start=BeaconUiMetrics.PageInset,end=BeaconUiMetrics.PageInset,top=BeaconUiMetrics.Gap4,bottom=BeaconUiMetrics.PageBottomInset),
                    itemSpacing=BeaconUiMetrics.MessageCardGap,
                ) {
                    items(visible,key={it.optString("local_source")+":"+it.optString("local_identity")}) { row ->
                        StateListCard(row) {
                            context.startActivity(Intent(context,StateWidgetActivity::class.java)
                                .putExtra("source",row.optString("local_source")).putExtra("identity",row.optString("local_identity")).putExtra("detail",true))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StateListCard(row: JSONObject, onOpen: () -> Unit) {
    val now=System.currentTimeMillis()
    val status=when {
        row.optBoolean("_waiting") -> "等待数据"
        row.optLong("expires_at_ms") in 1..now -> "已失效"
        row.optLong("stale_at_ms") in 1..now -> "数据较旧"
        else -> "数据有效"
    }
    BeaconCard(onClick=onOpen,modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
        BeaconListCardColumn(verticalInset=BeaconUiMetrics.MessageCardVerticalInset) {
            BeaconText(row.optString("title").ifBlank{row.optString("topic_key")},
                style=MaterialTheme.typography.titleMedium.copy(fontSize=BeaconListText.TitleSize,lineHeight=BeaconListText.TitleLineHeight),maxLines=2,overflow=TextOverflow.Ellipsis)
            BeaconText(MessageMarkdown.summary(row.optString("body")),Modifier.testTag("state-preview"),
                style=MaterialTheme.typography.bodySmall.copy(fontSize=BeaconListText.PreviewSize,lineHeight=BeaconListText.PreviewLineHeight),
                color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=BeaconListText.PreviewLines,overflow=TextOverflow.Ellipsis)
            BeaconText(listOf(if(row.optBoolean("local_imported"))"离线数据"else status,row.optString("application_name").ifBlank{"未关联应用"}).joinToString(" · "),
                style=MaterialTheme.typography.bodySmall.copy(fontSize=BeaconListText.SubtitleSize,lineHeight=BeaconListText.SubtitleLineHeight),
                color=MaterialTheme.colorScheme.onSurface.copy(alpha=BeaconListText.SubtitleAlpha),maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
