package io.github.beacon.app.widget

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
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
    fun defaultMode(config: JSONObject): Int = when {
        config.optString("global_color").isNotBlank() -> 2
        config.optString("color") == "semantic" -> 1
        else -> 0
    }

    fun withDefaultMode(config: JSONObject, mode: Int): JSONObject = JSONObject(config.toString()).apply {
        when (mode) {
            0 -> { put("global_color", ""); put("color", "fixed") }
            1 -> { put("global_color", ""); put("color", "semantic") }
            2 -> if (optString("global_color").isBlank()) put("global_color", values[1])
        }
    }

    fun resolve(config:JSONObject,id:String=""):Int? {
        val raw=config.optJSONObject("content_colors")?.optString(id).orEmpty().ifBlank{config.optString("global_color")}
        return values.drop(1).firstOrNull{it.equals(raw,true)}?.let{android.graphics.Color.parseColor(it)}
    }
}

@Composable
internal fun WidgetColorSettings(row:JSONObject,config:JSONObject,onChange:(JSONObject)->Unit,advanced:Boolean=true){
    var reset by remember{mutableStateOf(false)}
    val resolved = WidgetConfigRules.resolved(row, config)
    val template = resolved.optString("template", "horizontal_bars")
    val selected = WidgetConfigRules.selected(row, resolved)
    val textOnly = template in listOf("text", "text_list")
    val segmented = template == "segmented_ring"
    val bandBlocks = when (template) {
        "horizontal_bars", "single_ring", "nested_rings", "ring_grid" -> selected
        "overview_rings" -> selected.filter { it.optString("kind") in listOf("progress", "metric") }.take(1)
        else -> emptyList()
    }.filter { (it.optJSONArray("bands")?.length() ?: 0) > 0 }
    val usableBands = bandBlocks.any { WidgetColors.resolve(config, it.optString("id")) == null }
    SettingsGroup(if(advanced) "单项颜色覆盖" else "颜色与背景") {
        if (advanced) {
            SettingsPreferenceRows {
                SettingsHint("仅覆盖指定内容；选择“跟随默认”恢复默认配色。")
            }
            (if(template=="text") emptyList() else selected).forEach { block ->
                val id=block.optString("id")
                BeaconDropdownPreference(block.optString("label",id),
                    WidgetColors.names.mapIndexed { i,name -> if(i==0) "跟随默认" else name },
                    WidgetColors.values.indexOf(config.optJSONObject("content_colors")?.optString(id).orEmpty()).coerceAtLeast(0),
                    settingsRow=true,icon=BeaconIcons.Palette,itemLeading={ColorDot(it)}) { index ->
                    val colors=JSONObject(config.optJSONObject("content_colors")?.toString()?:"{}")
                    if(index==0) colors.remove(id) else colors.put(id,WidgetColors.values[index])
                    onChange(JSONObject(config.toString()).put("content_colors",colors))
                }
            }
        } else {
            if (!textOnly && !segmented) {
                BeaconDropdownPreference("默认配色",listOf("按内容区分","按数据状态","指定颜色"),
                    WidgetColors.defaultMode(config),settingsRow=true,icon=BeaconIcons.Palette) { index ->
                    onChange(WidgetColors.withDefaultMode(config,index))
                }
                SettingsPreferenceRows {
                    SettingsHint(when(WidgetColors.defaultMode(config)) {
                        1 -> "按数据标记显示：普通蓝色、警告橙色、严重红色。"
                        2 -> "使用下方颜色；已单独设色的内容保持原色。"
                        else -> "按内容顺序自动分配颜色，不随数据状态变化。"
                    })
                }
            }
            if (textOnly || segmented || WidgetColors.defaultMode(config)==2) {
                BeaconDropdownPreference(if(textOnly) "默认文字颜色" else "默认颜色",
                    if(textOnly) listOf("跟随背景")+WidgetColors.names.drop(1)
                    else if (segmented) listOf("按区段自动配色")+WidgetColors.names.drop(1)
                    else listOf("自动分色")+WidgetColors.names.drop(1),
                    WidgetColors.values.indexOf(config.optString("global_color")).coerceAtLeast(0),
                    settingsRow=true,icon=BeaconIcons.Palette,itemLeading={ColorDot(it)}) { index ->
                    onChange(if (!textOnly && !segmented && index == 0) WidgetColors.withDefaultMode(config, 0)
                        else JSONObject(config.toString()).put("global_color",WidgetColors.values[index]))
                }
                if (segmented) SettingsPreferenceRows { SettingsHint("手动选色后，各区段使用该颜色的不同深浅。") }
            }
            val overrides = config.optJSONObject("content_colors")
            val overrideCount = selected.count { overrides?.optString(it.optString("id")).orEmpty().isNotBlank() }
            if (overrideCount > 0) SettingsPreferenceRows {
                SettingsHint("$overrideCount 项已单独设色，优先使用单项颜色。")
            }
            if (bandBlocks.isNotEmpty()) SettingsPreferenceRows {
                BeaconPreferenceToggle("按区间着色",
                    if(usableBands) "进度图按数据提供的区间显示不同颜色"
                    else "手动颜色已覆盖区间颜色；改为自动配色后可用",
                    config.optBoolean("bands"),enabled=usableBands) { value ->
                    onChange(JSONObject(config.toString()).put("bands",value))
                }
                if (usableBands && config.optBoolean("bands")) SettingsHint("只改变进度图；数值文字仍使用默认或单项颜色。")
            }
            BeaconDropdownPreference("卡片背景",listOf("跟随系统","浅色","深色","透明"),
                listOf("system","light","dark","transparent").indexOf(config.optString("background","system")).coerceAtLeast(0),
                settingsRow=true,icon=BeaconIcons.ThemeMode) { index ->
                onChange(JSONObject(config.toString()).put("background",listOf("system","light","dark","transparent")[index]))
            }
            SettingsPreferenceRows {
                SettingsHint(if(config.optString("background")=="transparent")
                    "仅移除卡片底色，文字明暗仍跟随系统。" else "只改变卡片底色与文字明暗，不改变内容配色。")
            }
            val defaults=WidgetConfiguration.resetAppearance(config.toString())
            SettingsRow(stringResource(R.string.widget_restore_appearance),stringResource(R.string.widget_restore_appearance_hint),
                BeaconIcons.ThemeMode,iconTint=MaterialTheme.colorScheme.onSurface,
                enabled=!WidgetConfiguration.equivalent(config.toString(),defaults.toString(),config.optString("format")),
                command=true,onClick={reset=true})
        }
    }
    if(reset)BeaconConfirmDialog(stringResource(R.string.widget_restore_appearance),
        stringResource(R.string.widget_restore_appearance_confirm),onDismiss={reset=false},
        onConfirm={reset=false;onChange(WidgetConfiguration.resetAppearance(config.toString()))},
        confirmText=stringResource(R.string.widget_restore_defaults),danger=false)
}

@Composable private fun ColorDot(index:Int){
    if (index == 0) {
        androidx.compose.material3.Icon(BeaconIcons.Palette, "自动配色", Modifier.size(16.dp))
        return
    }
    val color=WidgetColors.values.getOrNull(index)?.takeIf{it.isNotEmpty()}?.let{Color(android.graphics.Color.parseColor(it))}?:MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(16.dp).background(color,CircleShape).border(1.dp,MaterialTheme.colorScheme.outline,CircleShape).semantics{contentDescription=WidgetColors.names.getOrElse(index){"自动"}+"色样"})
}
