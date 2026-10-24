package io.github.beacon.app.widget

import io.github.beacon.app.data.local.StateCache
import org.json.JSONArray
import org.json.JSONObject

/** Portable template settings; deliberately excludes host widget IDs and state payloads. */
object StatePreferencesBackup {
    const val MAX_BYTES=262144
    private val fields=setOf("source","identity","template","metrics","title","color","background","updated","bands")
    fun export(cache:StateCache, ids:IntArray):String{
        val items=JSONArray()
        ids.take(100).forEach{id->runCatching{JSONObject(cache.preference("widget:$id"))}.getOrNull()?.let{old->val clean=JSONObject();fields.forEach{key->if(old.has(key))clean.put(key,old.get(key))};items.put(clean)}}
        return JSONObject().put("format","beacon_widget_settings").put("version",1).put("templates",items).toString().also{require(it.toByteArray().size<=MAX_BYTES)}
    }
    fun parse(raw:String):List<JSONObject>{
        require(raw.toByteArray().size<=MAX_BYTES)
        val document=JSONObject(raw);require(document.getString("format")=="beacon_widget_settings"&&document.getInt("version")==1)
        val items=document.getJSONArray("templates");require(items.length()<=100)
        return (0 until items.length()).map{index->val item=items.getJSONObject(index);require(item.keys().asSequence().all{it in fields});require(item.optString("template") in WidgetRenderer.templates);require(item.optString("source").matches(Regex("[0-9a-f]{64}")));require(item.optString("identity").toByteArray().size<=193);require(item.optString("title").length<=40);val ids=item.optJSONArray("metrics");require(ids==null||ids.length()<=6);if(ids!=null)for(i in 0 until ids.length())require(ids.getString(i).toByteArray().size in 1..64);item}
    }
}
