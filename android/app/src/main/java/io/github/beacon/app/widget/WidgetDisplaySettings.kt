package io.github.beacon.app.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Modifier
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import org.json.JSONObject

/** A visible preview shares the desktop minute boundary; STOP cancels the one suspended timer. */
@Composable
internal fun rememberWidgetClock(row:JSONObject?,config:JSONObject):Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle,row?.toString(),config.toString()) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true) {
                now=System.currentTimeMillis()
                val next=WidgetCountdown.next(row,config,now)?:break
                kotlinx.coroutines.delay((next-now).coerceAtLeast(1))
            }
        }
    }
    return now
}

@Composable
internal fun WidgetDisplaySettings(row:JSONObject,config:JSONObject,onChange:(JSONObject)->Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SettingsGroupGap)) {
    SettingsGroup("点击行为") {
        BeaconDropdownPreference("单击小组件",listOf("不执行操作","查看完整内容"),
            if(config.optString("tap_action")=="detail")1 else 0,settingsRow=true,icon=BeaconIcons.Info){index->
            onChange(JSONObject(config.toString()).put("tap_action",if(index==1)"detail"else"none"))
        }
        Column(Modifier.padding(horizontal=BeaconUiMetrics.CardInset)) {
            SettingsHint("查看内容不会打开外部链接。")
        }
    }
    val selected=WidgetConfigRules.selected(row,config)
    if(selected.isNotEmpty())SettingsGroup("内容名称") {
        Column(Modifier.padding(BeaconUiMetrics.CardInset)) {
            SettingsHint(if(config.optString("template")=="nested_rings")"留空使用原名称，最多8字；按外环到内环排列。"else"仅对当前组件生效，留空使用原名称，最多8字。")
            selected.take(8).forEach { block ->
                val id=block.optString("id")
                BeaconTextField(config.optJSONObject("short_labels")?.optString(id).orEmpty(),{ raw ->
                    val clean=raw.replace("\n","").replace("\r","")
                    val value=if(clean.codePointCount(0,clean.length)>8)clean.substring(0,clean.offsetByCodePoints(0,8))else clean
                    val labels=JSONObject(config.optJSONObject("short_labels")?.toString()?:"{}")
                    if(value.isBlank())labels.remove(id)else labels.put(id,value)
                    onChange(JSONObject(config.toString()).put("short_labels",labels))
                },Modifier.fillMaxWidth(),label={BeaconText(block.optString("label",id))},labelText=block.optString("label",id),singleLine=true,
                    placeholder=block.optString("label",id))
            }
        }
    }
    }
}
