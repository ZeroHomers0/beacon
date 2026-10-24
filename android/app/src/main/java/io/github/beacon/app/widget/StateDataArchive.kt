package io.github.beacon.app.widget

import io.github.beacon.app.data.local.StateCache
import org.json.JSONArray
import org.json.JSONObject

/** Offline snapshots have a separate namespace and can never overwrite live reception cursors. */
object StateDataArchive {
    const val MAX_BYTES=16*1024*1024
    fun export(cache:StateCache):String=JSONObject().put("format","beacon_state_snapshot").put("version",1).put("states",JSONArray(cache.all())).toString().also{require(it.toByteArray().size<=MAX_BYTES)}
    fun restore(cache:StateCache,raw:String){
        require(raw.toByteArray().size<=MAX_BYTES)
        val document=JSONObject(raw);require(document.getString("format")=="beacon_state_snapshot"&&document.getInt("version")==1)
        val rows=document.getJSONArray("states");require(rows.length()<=10000)
        val sources=mutableMapOf<String,MutableList<JSONObject>>()
        for(i in 0 until rows.length()){
            val row=rows.getJSONObject(i)
            val original=row.getString("local_source");require(original.matches(Regex("[0-9a-f]{64}")))
            val origin=row.optString("archive_origin",original);require(origin.matches(Regex("[0-9a-f]{64}")))
            row.put("archive_origin",origin)
            val source=java.security.MessageDigest.getInstance("SHA-256").digest(("offline:"+origin).toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
            row.remove("local_source");row.remove("local_identity");row.put("local_imported",true);row.put("stale_at_ms",1)
            sources.getOrPut(source){mutableListOf()}.add(row)
        }
        val db=cache.writableDatabase;db.beginTransaction()
        try{for((source,records)in sources)cache.apply(source,records,"offline",false);db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
}
