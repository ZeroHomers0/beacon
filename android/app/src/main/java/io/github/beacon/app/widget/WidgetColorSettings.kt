package io.github.beacon.app.widget

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import org.json.JSONObject

internal object WidgetColors {
    val names=listOf("自动","蓝色","紫色","绿色","橙色","红色","青色","粉色")
    val values=listOf("","#3478F6","#8B70E8","#29B86A","#E99A32","#E65B65","#29AEB6","#D775B1")
    fun resolve(config:JSONObject,id:String=""):Int? {
        val raw=config.optJSONObject("content_colors")?.optString(id).orEmpty().ifBlank{config.optString("global_color")}
        return values.drop(1).firstOrNull{it.equals(raw,true)}?.let{android.graphics.Color.parseColor(it)}
    }
}

@Composable
internal fun WidgetColorSettings(row:JSONObject,config:JSONObject,onChange:(JSONObject)->Unit,advanced:Boolean=true){
    SettingsGroup(if(advanced)"单项颜色"else"颜色与背景") {
        if(!advanced)BeaconDropdownPreference("所有内容的颜色",WidgetColors.names.mapIndexed{i,name->if(i==0&&config.optString("color")=="semantic")"自动（按状态变色）"else name},WidgetColors.values.indexOf(config.optString("global_color")).coerceAtLeast(0),settingsRow=true,icon=BeaconIcons.Palette,itemLeading={ColorDot(it)}){index->
            onChange(JSONObject(config.toString()).put("global_color",WidgetColors.values[index]).apply{remove("content_colors")})
        }
        (if(!advanced||config.optString("template")=="text")emptyList()else WidgetConfigRules.selected(row,config)).forEach{block->
            val id=block.optString("id")
            val banded=config.optBoolean("bands")&&WidgetColors.resolve(config,id)==null&&(block.optJSONArray("bands")?.length()?:0)>0
            BeaconDropdownPreference(block.optString("label",id)+if(banded)"（使用分段颜色）"else"的颜色",WidgetColors.names.mapIndexed{i,name->if(i==0)"使用统一设置"else name},WidgetColors.values.indexOf(config.optJSONObject("content_colors")?.optString(id).orEmpty()).coerceAtLeast(0),settingsRow=true,icon=BeaconIcons.Palette,itemLeading={ColorDot(it)}){index->
                val colors=JSONObject(config.optJSONObject("content_colors")?.toString()?:"{}")
                if(index==0)colors.remove(id)else colors.put(id,WidgetColors.values[index])
                onChange(JSONObject(config.toString()).put("content_colors",colors))
            }
        }
        if(!advanced){
            Column(Modifier.padding(horizontal=BeaconUiMetrics.CardInset)){
                val available=WidgetConfigRules.selected(row,config).any{(it.optJSONArray("bands")?.length()?:0)>0}
                BeaconPreferenceToggle("显示分段提示颜色",if(available)"手选颜色优先；自动颜色才使用分段范围"else"当前内容未提供分段范围",config.optBoolean("bands"),enabled=available){value->onChange(JSONObject(config.toString()).put("bands",value))}
            }
            BeaconDropdownPreference("内容配色",listOf("自选颜色","按状态变色"),if(config.optString("color")=="semantic")1 else 0,settingsRow=true,icon=BeaconIcons.Palette){i->onChange(JSONObject(config.toString()).put("color",if(i==0)"fixed"else"semantic"))}
            BeaconDropdownPreference("卡片背景",listOf("跟随手机主题","浅色","深色","透明"),listOf("system","light","dark","transparent").indexOf(config.optString("background","system")),settingsRow=true,icon=BeaconIcons.ThemeMode){i->onChange(JSONObject(config.toString()).put("background",listOf("system","light","dark","transparent")[i]))}
        }
    }
}

@Composable private fun ColorDot(index:Int){
    val color=WidgetColors.values.getOrNull(index)?.takeIf{it.isNotEmpty()}?.let{Color(android.graphics.Color.parseColor(it))}?:MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(16.dp).background(color,CircleShape).border(1.dp,MaterialTheme.colorScheme.outline,CircleShape).semantics{contentDescription=WidgetColors.names.getOrElse(index){"自动"}+"色样"})
}
