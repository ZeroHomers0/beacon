package io.github.beacon.app.widget

import androidx.compose.runtime.Composable
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import org.json.JSONObject

/** Discrete preferences are bounded again at render time; imported values cannot escape limits. */
object WidgetTuning {
    fun values(key:String)=if(key=="stroke_scale")listOf(80,100,115)else if(key=="label_scale")listOf(85,100,110)else listOf(85,100,115)
    fun factor(config:JSONObject,key:String):Float=config.optInt(key,100).takeIf{it in values(key)}?.div(100f)?:1f
    fun stroke(config:JSONObject,count:Int):Float=if(config.optString("template")=="nested_rings"&&count==3)factor(config,"stroke_scale").coerceAtMost(1.1f)else factor(config,"stroke_scale")
    fun error(config:JSONObject):String? {
        if(config.optString("tap_action","none") !in listOf("none","detail"))return "请选择有效的点击行为"
        val labels=config.optJSONObject("short_labels")
        if(labels!=null&&(labels.length()>32||labels.keys().asSequence().any{key->
            val value=labels.optString(key)
            value.codePointCount(0,value.length)>8||value.any{it.isISOControl()}
        }))return "显示短名称最多8个字符，请缩短后保存"
        return listOf("number_scale","label_scale","stroke_scale").firstOrNull{config.has(it)&&config.optInt(it) !in values(it)}?.let{"字号或粗细超出安全范围，请重新选择"}
    }
}

@Composable internal fun WidgetTuningSettings(config:JSONObject,onChange:(JSONObject)->Unit){
    val type=config.optString("template")
    if(type in listOf("text","text_list")){
        SettingsGroup("字号与排版"){
            val sizes=listOf(12,14,16,18,20,24)
            BeaconDropdownPreference("文字大小",sizes.map{"$it 号"},sizes.indexOf(config.optInt("text_size",16)).coerceAtLeast(0),settingsRow=true,icon=BeaconIcons.Tune){i->onChange(JSONObject(config.toString()).put("text_size",sizes[i]))}
        }
        return
    }
    SettingsGroup("字号与粗细"){
        listOf("number_scale" to "数字大小","label_scale" to "说明文字大小").forEach{(key,label)->
            BeaconDropdownPreference(label,listOf("小","标准","大"),WidgetTuning.values(key).indexOf(config.optInt(key,100)).coerceAtLeast(0),settingsRow=true,icon=BeaconIcons.Tune){i->onChange(JSONObject(config.toString()).put(key,WidgetTuning.values(key)[i]))}
        }
        if(type !in listOf("numbers","sparkline","countdown","overview_list"))BeaconDropdownPreference("图形粗细",listOf("较细","标准","较粗"),WidgetTuning.values("stroke_scale").indexOf(config.optInt("stroke_scale",100)).coerceAtLeast(0),settingsRow=true,icon=BeaconIcons.Tune){i->onChange(JSONObject(config.toString()).put("stroke_scale",WidgetTuning.values("stroke_scale")[i]))}
    }
}
