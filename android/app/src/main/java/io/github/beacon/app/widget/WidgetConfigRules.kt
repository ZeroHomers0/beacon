package io.github.beacon.app.widget

import org.json.JSONObject

object WidgetConfigRules {
    fun selected(row:JSONObject, config:JSONObject):List<JSONObject>{
        val blocks=row.optJSONArray("blocks")?:return emptyList();val available=(0 until blocks.length()).mapNotNull{blocks.optJSONObject(it)}.filter{it.optString("kind") in listOf("progress","metric","segments")}
        val ids=config.optJSONArray("metrics")?:return available
        if(ids.length()==0)return available
        return (0 until ids.length()).mapNotNull{i->available.firstOrNull{it.optString("id")==ids.optString(i)}}
    }
    fun error(row:JSONObject,config:JSONObject):String?{
        val blocks=selected(row,config);if(blocks.isEmpty())return "暂无绑定指标"
        val type=config.optString("template","horizontal_bars")
        if(type !in WidgetRenderer.templates)return "请选择模板"
        if(type=="segmented_ring")return if(blocks.size==1&&blocks[0].optString("kind")=="segments")null else "分段环需要一个组成数据块"
        if(blocks.any{it.optString("kind")=="segments"})return "此模板不能展示组成数据，请重新选择指标"
        val max=when(type){"single_ring"->1;"nested_rings"->3;"vertical_bars"->6;else->4}
        val min=if(type in listOf("nested_rings","vertical_bars","ring_grid"))2 else 1
        if(blocks.size !in min..max)return "此模板需要 $min–$max 个指标"
        if(type !in listOf("numbers","horizontal_bars")&&blocks.any{!it.optDouble("max").isFinite()||it.optBoolean("indeterminate")})return "此模板需要明确范围，请改用数字或横条"
        if(type=="vertical_bars"&&blocks.any{it.optString("unit")!=blocks[0].optString("unit")||it.optDouble("min",0.0)!=blocks[0].optDouble("min",0.0)||it.optDouble("max")!=blocks[0].optDouble("max")})return "柱图需要相同单位与范围"
        return null
    }
}
