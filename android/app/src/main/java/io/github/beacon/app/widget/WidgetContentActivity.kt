package io.github.beacon.app.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.beacon.app.data.local.UiStyleStore
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.BeaconTheme
import org.json.JSONObject

/** An opt-in desktop tap opens content only; it never exposes an editing or sending action. */
class WidgetContentActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val model=ViewModelProvider(this)[WidgetEditorViewModel::class.java]
        val style=UiStyleStore(this)
        setContent { BeaconTheme(style.load(),style.loadMode()) {
            val snapshot by model.snapshot.collectAsStateWithLifecycle()
            val failed by model.failed.collectAsStateWithLifecycle()
            ObserveStateChanges{model.reload(id)}
            LaunchedEffect(id){model.reload(id)}
            val config=runCatching{JSONObject(snapshot?.stored?:"{}")}.getOrElse{JSONObject()}
            val row=snapshot?.rows?.firstOrNull{it.optString("local_source")==config.optString("source")&&it.optString("local_identity")==config.optString("identity")}
            ObserveStateDeadline(listOfNotNull(row)){model.reload(id)}
            val bar=rememberBeaconBarBehavior()
            BeaconScaffold(topBar={BeaconTopBar("内容详情",false,bar,navigationIcon={BeaconIconButton(onClick={finish()}){Icon(BeaconIcons.Back,"返回")}},actions={})},bottomBar={}){padding->
                BeaconSettingsList(rememberLazyListState(),Modifier.fillMaxSize().padding(padding)) {
                    item { SettingsGroup("数据概况") { Column(Modifier.padding(BeaconUiMetrics.CardInset)) {
                        if(snapshot==null)SettingsHint(if(failed)"内容加载失败，请重试。"else"正在加载内容…")
                        else {
                            BeaconText(row?.optString("title")?:"暂无内容")
                            SettingsHint(WidgetHealth.description(row,online=WidgetHealth.online(this@WidgetContentActivity)))
                            val guidance=WidgetHealth.guidance(row,WidgetHealth.online(this@WidgetContentActivity))
                            if(guidance.isNotBlank())SettingsHint(guidance)
                            row?.let{BeaconText(it.optString("body"))}
                        }
                    }}}
                    if(row!=null)item { SettingsGroup("完整内容") { Column(Modifier.padding(BeaconUiMetrics.CardInset)) {
                        io.github.beacon.app.feature.messages.StructuredBlocksPanel(row.optJSONArray("blocks")?.toString()?:"[]")
                        io.github.beacon.app.feature.messages.MessageActionsPanel(io.github.beacon.app.data.remote.MessageActionsCodec.decode(row),"")
                    }}}
                    if(failed)item { BeaconButton(onClick={model.reload(id)}){BeaconText("重试")} }
                }
            }
        }}
    }
}
