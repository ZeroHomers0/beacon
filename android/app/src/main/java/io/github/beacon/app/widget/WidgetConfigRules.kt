package io.github.beacon.app.widget

import org.json.JSONObject

object WidgetConfigRules {
    fun resolved(row:JSONObject?,config:JSONObject):JSONObject=if(config.optString("template")=="auto"&&row!=null&&!row.optBoolean("_waiting"))JSONObject(config.toString()).apply{put("template",recommend(row));put("metrics",org.json.JSONArray(defaults(row,this).map{it.optString("id")}))}else config

    fun recommend(row:JSONObject):String {
        val blocks=available(row)
        return when { blocks.isEmpty()->"text";blocks.any{it.optString("kind") in listOf("progress","metric")}->if(blocks.filter{it.optString("kind") in listOf("progress","metric")}.all{!it.has("max")})"numbers"else"horizontal_bars";blocks.first().optString("kind")=="list"->"text_list";blocks.first().optString("kind")=="sparkline"->"sparkline";else->"segmented_ring" }
    }
    fun defaults(row:JSONObject,config:JSONObject):List<JSONObject> = available(row).filter{when(config.optString("template")){"text_list"->it.optString("kind")=="list";"sparkline"->it.optString("kind")=="sparkline";"segmented_ring"->it.optString("kind")=="segments";"text"->false;else->it.optString("kind") in listOf("progress","metric")}}.take(if(config.optString("template") in listOf("text_list","sparkline","segmented_ring"))1 else 4)
    fun available(row:JSONObject):List<JSONObject> {
        val blocks=row.optJSONArray("blocks")?:org.json.JSONArray()
        return (0 until blocks.length()).mapNotNull{blocks.optJSONObject(it)}.filter{it.optString("kind") in listOf("progress","metric","segments","list","sparkline")}
    }
    fun selected(row:JSONObject, config:JSONObject):List<JSONObject>{
        val available=available(row)
        val ids=config.optJSONArray("metrics")?:return available
        return (0 until ids.length()).mapNotNull{i->available.firstOrNull{it.optString("id")==ids.optString(i)}}
    }
    fun error(row:JSONObject,config:JSONObject):String?{
        if(row.optBoolean("_topic_deleted"))return "主题已删除，请恢复主题或选择其他内容"
        if(row.optBoolean("_waiting"))return null
        if(config.optString("template")=="auto")return error(row,resolved(row,config))
        WidgetTuning.error(config)?.let{return it}
        val type=config.optString("template","horizontal_bars")
        if(type=="text")return if(row.optString("body").isBlank())"这条主题没有正文"else null
        val requested=config.optJSONArray("metrics")
        if(requested!=null){val available=row.optJSONArray("blocks")?:org.json.JSONArray();val ids=(0 until available.length()).map{available.getJSONObject(it).optString("id")};if((0 until requested.length()).any{requested.optString(it) !in ids})return "所选内容已失效，请重新选择"}
        val blocks=selected(row,config);if(blocks.isEmpty())return "暂无绑定内容"
        if(type !in WidgetRenderer.templates)return "请选择模板"
        if(type=="text_list")return if(blocks.size==1&&blocks[0].optString("kind")=="list")null else "请选择一组文字列表"
        if(type=="sparkline")return if(blocks.size==1&&blocks[0].optString("kind")=="sparkline")null else "请选择一组趋势数据"
        if(blocks.any{it.optString("kind")=="sparkline"})return "此样式不支持趋势数据"
        if(blocks.any{it.optString("kind")=="list"})return "此样式仅支持数值内容"
        if(type=="segmented_ring")return if(blocks.size==1&&blocks[0].optString("kind")=="segments")null else "请选择一组占比数据"
        if(blocks.any{it.optString("kind")=="segments"})return "此模板不能展示组成数据，请重新选择内容"
        val max=when(type){"single_ring"->1;"nested_rings"->3;"vertical_bars"->6;"numbers"->if(config.optString("format")=="LARGE")8 else 4;"horizontal_bars"->if(config.optString("format")=="LARGE")6 else 4;else->4}
        val min=if(type in listOf("nested_rings","vertical_bars","ring_grid"))2 else 1
        if(blocks.size !in min..max)return "此样式需要 ${if(min==max)"$min"else"$min–$max"} 项内容"
        if(type !in listOf("numbers","horizontal_bars")&&blocks.any{!it.optDouble("max").isFinite()||it.optBoolean("indeterminate")})return "此模板需要明确范围，请改用数字或横条"
        if(type=="vertical_bars"&&blocks.any{it.optString("unit")!=blocks[0].optString("unit")||it.optDouble("min",0.0)!=blocks[0].optDouble("min",0.0)||it.optDouble("max")!=blocks[0].optDouble("max")})return "柱图需要相同单位与范围"
        return null
    }

    fun layoutError(row:JSONObject,config:JSONObject,width:Int,height:Int):String? {
        if(config.optString("template")=="auto"&&!row.optBoolean("_waiting"))return layoutError(row,resolved(row,config),width,height)
        error(row,config)?.let{return it}
        if(row.optBoolean("_waiting"))return null
        val blocks=selected(row,config);val type=config.optString("template","horizontal_bars")
        val scale=kotlin.math.min(width/300f,height/220f).coerceAtLeast(.75f)
        val pad=kotlin.math.max(12f,kotlin.math.min(width,height)*(40f/440f))
        val content=height-2*pad-28*scale-(if(type=="single_ring"&&height<150)0f else 16*scale)
        val count=blocks.size
        val small=when(type){
            "sparkline"->content<80*scale
            "horizontal_bars"->content/count<30*scale
            "numbers"->content/((count+1)/2).coerceAtLeast(1)<40*scale
            "nested_rings"->content/count<36*scale
            "ring_grid"->content/(if(count>2)2 else 1)<45*scale||(width-2*pad)/2<40*scale
            "vertical_bars"->content<90*scale||count>3&&width<240
            "segmented_ring"->content/(blocks[0].optJSONArray("items")?.length()?:1).coerceAtLeast(1)<16*scale
            else->content<36*scale
        }
        return if(small)"当前尺寸空间不足，请减少内容或选择其他模板" else null
    }
}
