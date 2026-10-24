package io.github.beacon.app.widget

import androidx.compose.runtime.Composable
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
internal fun WidgetColorSettings(row:JSONObject,config:JSONObject,onChange:(JSONObject)->Unit){
    SettingsGroup("内容颜色与字号") {
        BeaconDropdownPreference("所有内容的颜色",WidgetColors.names,WidgetColors.values.indexOf(config.optString("global_color")).coerceAtLeast(0),settingsRow=true,icon=BeaconIcons.Palette){index->
            onChange(JSONObject(config.toString()).put("global_color",WidgetColors.values[index]).apply{remove("content_colors")})
        }
        (if(config.optString("template")=="text")emptyList()else WidgetConfigRules.selected(row,config)).forEach{block->
            val id=block.optString("id")
            BeaconDropdownPreference(block.optString("label",id)+"的颜色",WidgetColors.names.mapIndexed{i,name->if(i==0)"使用统一设置"else name},WidgetColors.values.indexOf(config.optJSONObject("content_colors")?.optString(id).orEmpty()).coerceAtLeast(0),settingsRow=true,icon=BeaconIcons.Palette){index->
                val colors=JSONObject(config.optJSONObject("content_colors")?.toString()?:"{}")
                if(index==0)colors.remove(id)else colors.put(id,WidgetColors.values[index])
                onChange(JSONObject(config.toString()).put("content_colors",colors))
            }
        }
        if(config.optString("template") in listOf("text","text_list")){
            val sizes=listOf(12,14,16,18,20,24)
            BeaconDropdownPreference("文字大小",sizes.map{"$it sp"},sizes.indexOf(config.optInt("text_size",16)).coerceAtLeast(0),settingsRow=true,icon=BeaconIcons.Tune){index->onChange(JSONObject(config.toString()).put("text_size",sizes[index]))}
        }
    }
}
