package io.github.beacon.app.widget

import org.json.JSONObject

object WidgetConfigRules {
    fun available(row:JSONObject):List<JSONObject> {
        val blocks=row.optJSONArray("blocks")?:org.json.JSONArray()
        return (0 until blocks.length()).mapNotNull{blocks.optJSONObject(it)}.filter{it.optString("kind") in listOf("progress","metric","segments","list")}
    }
    fun selected(row:JSONObject, config:JSONObject):List<JSONObject>{
        val available=available(row)
        val ids=config.optJSONArray("metrics")?:return available
        return (0 until ids.length()).mapNotNull{i->available.firstOrNull{it.optString("id")==ids.optString(i)}}
    }
    fun error(row:JSONObject,config:JSONObject):String?{
        val type=config.optString("template","horizontal_bars")
        if(type=="text")return if(row.optString("body").isBlank())"这条主题没有正文"else null
        val requested=config.optJSONArray("metrics")
        if(requested!=null){val available=row.optJSONArray("blocks")?:org.json.JSONArray();val ids=(0 until available.length()).map{available.getJSONObject(it).optString("id")};if((0 until requested.length()).any{requested.optString(it) !in ids})return "所选内容已失效，请重新选择"}
        val blocks=selected(row,config);if(blocks.isEmpty())return "暂无绑定内容"
        if(type !in WidgetRenderer.templates)return "请选择模板"
        if(type=="text_list")return if(blocks.size==1&&blocks[0].optString("kind")=="list")null else "请选择一组文字列表"
        if(blocks.any{it.optString("kind")=="list"})return "此样式仅支持数值内容"
        if(type=="segmented_ring")return if(blocks.size==1&&blocks[0].optString("kind")=="segments")null else "请选择一组占比数据"
        if(blocks.any{it.optString("kind")=="segments"})return "此模板不能展示组成数据，请重新选择内容"
        val max=when(type){"single_ring"->1;"nested_rings"->3;"vertical_bars"->6;else->4}
        val min=if(type in listOf("nested_rings","vertical_bars","ring_grid"))2 else 1
        if(blocks.size !in min..max)return "此样式需要 ${if(min==max)"$min"else"$min–$max"} 项内容"
        if(type !in listOf("numbers","horizontal_bars")&&blocks.any{!it.optDouble("max").isFinite()||it.optBoolean("indeterminate")})return "此模板需要明确范围，请改用数字或横条"
        if(type=="vertical_bars"&&blocks.any{it.optString("unit")!=blocks[0].optString("unit")||it.optDouble("min",0.0)!=blocks[0].optDouble("min",0.0)||it.optDouble("max")!=blocks[0].optDouble("max")})return "柱图需要相同单位与范围"
        return null
    }

    fun layoutError(row:JSONObject,config:JSONObject,width:Int,height:Int):String? {
        error(row,config)?.let{return it}
        val blocks=selected(row,config);val type=config.optString("template","horizontal_bars")
        val scale=kotlin.math.min(width/300f,height/220f).coerceAtLeast(.75f)
        val pad=kotlin.math.max(12f,kotlin.math.min(width,height)*(40f/440f))
        val content=height-2*pad-28*scale-(if(type=="single_ring"&&height<150)0f else 16*scale)
        val count=blocks.size
        val small=when(type){
            "horizontal_bars"->content/count<30*scale
            "numbers"->content/(if(count>2)2 else 1)<40*scale
            "nested_rings"->content/count<36*scale
            "ring_grid"->content/(if(count>2)2 else 1)<45*scale||(width-2*pad)/2<40*scale
            "vertical_bars"->content<90*scale||count>3&&width<240
            "segmented_ring"->content/(blocks[0].optJSONArray("items")?.length()?:1).coerceAtLeast(1)<16*scale
            else->content<36*scale
        }
        return if(small)"当前尺寸空间不足，请减少内容或选择其他模板" else null
    }
}
