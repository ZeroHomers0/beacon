package io.github.beacon.app.widget

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import org.json.JSONObject

object WidgetHealth {
    fun online(context:android.content.Context):Boolean=runCatching{
        val manager=context.getSystemService(android.net.ConnectivityManager::class.java)
        manager.activeNetwork!=null
    }.getOrDefault(true)
    fun description(row:JSONObject?,now:Long=System.currentTimeMillis(),online:Boolean=true):String=when{
        row==null->"原内容已删除或尚未同步，请检查主题绑定"
        row.optBoolean("_waiting")->"等待首次上报"
        row.optLong("expires_at_ms") in 1..now->"已过有效期，等待新数据"
        row.optLong("stale_at_ms") in 1..now->"数据较旧，展示最后一次结果"
        !online->"网络未连通，展示最后一次结果"
        else->"数据有效"
    }
}

@Composable internal fun WidgetSimpleAppearance(row:JSONObject,config:JSONObject,onChange:(JSONObject)->Unit){
    WidgetColorSettings(row,config,onChange,advanced=false)
}

@Composable internal fun WidgetMoreSettings(row:JSONObject,config:JSONObject,onChange:(JSONObject)->Unit){
    val type = config.optString("template")
    if(type == "text") return
    val numeric = type != "text_list"
    var expanded by remember{mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.SettingsGroupGap)){
        SettingsGroup("更多设置"){
            SettingsRow(if(numeric)"单项颜色与数值格式"else"列表颜色","颜色优先级：单项设置 > 统一设置 > 分段提示 > 状态或自动颜色",BeaconIcons.Tune,iconTint=androidx.compose.material3.MaterialTheme.colorScheme.onSurface,onClick={expanded=!expanded},expanded=expanded)
        }
        if(expanded){
            WidgetColorSettings(row,config,onChange,advanced=true)
            if(numeric) SettingsGroup("数值格式"){
                val decimals=listOf(-1,0,1,2,3)
                BeaconDropdownPreference("小数位数",listOf("自动","整数","1位","2位","3位"),decimals.indexOf(config.optInt("decimals",-1)).coerceAtLeast(0),settingsRow=true,icon=BeaconIcons.Tune){i->onChange(JSONObject(config.toString()).put("decimals",decimals[i]))}
                BeaconDropdownPreference("单位位置",listOf("数值后","数值前","隐藏单位"),listOf("after","before","hidden").indexOf(config.optString("unit_position","after")).coerceAtLeast(0),settingsRow=true,icon=BeaconIcons.Tune){i->onChange(JSONObject(config.toString()).put("unit_position",listOf("after","before","hidden")[i]))}
                Column(Modifier.padding(horizontal=BeaconUiMetrics.CardInset)){
                    BeaconPreferenceToggle("显示趋势箭头","使用发送方提供的上升、下降或持平趋势",config.optBoolean("trend",true)){value->onChange(JSONObject(config.toString()).put("trend",value))}
                }
            }
        }
    }
}

@Composable internal fun WidgetListSettings(config:JSONObject,onChange:(JSONObject)->Unit){
    if(config.optString("template")!="text_list")return
    SettingsGroup("榜单显示"){
        BeaconDropdownPreference("起始排名",(1..12).map{"第${it}条"},config.optInt("list_start",1).coerceIn(1,12)-1,settingsRow=true,icon=BeaconIcons.Tune){i->onChange(JSONObject(config.toString()).put("list_start",i+1))}
        BeaconDropdownPreference("最多显示",(1..12).map{"${it}条"},config.optInt("list_count",12).coerceIn(1,12)-1,settingsRow=true,icon=BeaconIcons.Tune){i->onChange(JSONObject(config.toString()).put("list_count",i+1))}
        BeaconDropdownPreference("每条行数",listOf("一行","两行"),config.optInt("list_lines",1).coerceIn(1,2)-1,settingsRow=true,icon=BeaconIcons.Tune){i->onChange(JSONObject(config.toString()).put("list_lines",i+1))}
        Column(Modifier.padding(horizontal=BeaconUiMetrics.CardInset)){
            BeaconPreferenceToggle("显示排名序号","序号对应原榜单排名",config.optBoolean("list_rank",true)){value->onChange(JSONObject(config.toString()).put("list_rank",value))}
        }
    }
}
