package io.github.beacon.app.data.remote

import org.json.JSONArray
import org.json.JSONObject

/** Unknown or malformed attachments never prevent delivery of the text fallback. */
object StructuredBlocksCodec {
    fun decode(row: JSONObject): String {
        if (row.opt("schema_version") != 1) return "[]"
        val blocks = row.optJSONArray("blocks") ?: return "[]"
        if (blocks.length() > 16 || blocks.toString().toByteArray(Charsets.UTF_8).size > 32768) return "[]"
        val result = JSONArray()
        val ids = mutableSetOf<String>()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            val id = block.opt("id") as? String ?: continue
            if (id.isEmpty() || id.toByteArray(Charsets.UTF_8).size > 64 || id.any { it.isISOControl() } || id in ids) continue
            val kind = block.optString("kind")
            if(kind !in setOf("progress","metric")) {
                if(validRich(block)){ids.add(id);result.put(block)}
                continue
            }
            if (block.optString("label").toByteArray(Charsets.UTF_8).size > 128 || block.optString("unit").toByteArray(Charsets.UTF_8).size > 32) continue
            val unknown = block.optBoolean("indeterminate", false)
            if (unknown) {
                if (kind != "progress" || listOf("value", "min", "max").any { block.has(it) }) continue
            } else {
                val value = block.opt("value") as? Number ?: continue
                if (!value.toDouble().isFinite() || kotlin.math.abs(value.toDouble()) > 1e15) continue
                if (kind == "progress" && !block.has("max")) continue
                if (kind == "metric" && block.has("min") != block.has("max")) continue
                if (block.has("max")) {
                    val max = (block.opt("max") as? Number)?.toDouble() ?: continue
                    val min = if (block.has("min")) (block.opt("min") as? Number)?.toDouble() ?: continue else 0.0
                    if (!max.isFinite() || !min.isFinite() || max <= min || value.toDouble() !in min..max) continue
                }
            }
            ids.add(id)
            result.put(block)
        }
        return result.toString()
    }
    private fun validRich(b: JSONObject): Boolean {
        val kind=b.optString("kind")
        if(kind !in setOf("kv","list","countdown","timeline","pipeline","alert","sparkline","segments"))return false
        if(b.optString("text").toByteArray().size>2048)return false
        if(kind=="alert")return b.optString("text").isNotEmpty()
        if(kind=="countdown")return b.opt("target_at_ms") is Number && b.optLong("target_at_ms") in 0..9007199254740991L
        if(kind=="sparkline"){
            val points=b.optJSONArray("points")?:return false;if(points.length() !in 1..120)return false;var last=-1L
            for(i in 0 until points.length()){val point=points.optJSONObject(i)?:return false;val at=point.optLong("at_ms",-1);if(at<=last||point.opt("value") !is Number||!point.optDouble("value").isFinite())return false;last=at};return true
        }
        val items=b.optJSONArray("items")?:return false;if(items.length() !in 1..12)return false
        val ids=mutableSetOf<String>();var sum=0.0
        for(i in 0 until items.length()){
            val item=items.optJSONObject(i)?:return false;val id=item.optString("id");if(id.isEmpty()||id.toByteArray().size>64||!ids.add(id)||item.optString("label").toByteArray().size>128||item.optString("text").toByteArray().size>2048)return false
            when(kind){
                "kv"->if(item.optString("label").isEmpty())return false
                "list"->if(item.optString("text").isEmpty())return false
                "timeline"->if(item.optLong("at_ms",-1)<0||item.optString("status") !in setOf("pending","active","completed","failed"))return false
                "pipeline"->if(item.optString("status") !in setOf("pending","running","completed","failed","skipped"))return false
                "segments"->{val value=item.optDouble("value");if(!value.isFinite()||value<0)return false;sum+=value}
            }
        }
        return kind!="segments"||(items.length()<=6&&b.optDouble("total")>0&&sum<=b.optDouble("total"))
    }

}
