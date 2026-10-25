package io.github.beacon.app.data.local

import android.content.ContentValues
import android.content.Context
import io.github.beacon.app.domain.model.*
import org.json.JSONArray

/** Runtime-owned, transactional SQLite preferences are visible across app processes. */
class TemporaryMuteStore(private val context: Context) {
    private fun key(scope:MuteScope):String {
        scope.validate()
        return "mute:"+JSONArray(listOf(scope.kind.name,scope.source,scope.identity)).toString()
    }
    fun snapshot(scope:MuteScope):MuteSnapshot = StateCache(context).use { cache ->
        MuteSnapshot(cache.preference(key(scope)).toLongOrNull()?:0,
            cache.preference(key(MuteScope.GLOBAL)).toLongOrNull()?:0)
    }
    fun rules():MuteRules = StateCache(context).use { cache ->
        val values=mutableMapOf<MuteScope,Long>()
        cache.readableDatabase.rawQuery("SELECT key,value FROM preferences WHERE key LIKE 'mute:%' LIMIT 1001",null).use { cursor ->
            while(cursor.moveToNext()) {
                require(values.size<1000)
                val fields=JSONArray(cursor.getString(0).removePrefix("mute:"))
                val scope=MuteScope(MuteKind.valueOf(fields.getString(0)),fields.getString(1),fields.getString(2))
                scope.validate();values[scope]=cursor.getString(1).toLong()
            }
        }
        MuteRules(values)
    }
    fun save(scope:MuteScope,untilMs:Long) {
        require(untilMs>=0)
        val key=key(scope)
        StateCache(context).use { cache ->
            val db=cache.writableDatabase;db.beginTransaction()
            try {
                db.delete("preferences","key LIKE 'mute:%' AND CAST(value AS INTEGER)<=?",arrayOf(System.currentTimeMillis().toString()))
                if(untilMs==0L)db.delete("preferences","key=?",arrayOf(key))
                else {
                    val existing=cache.preference(key).isNotBlank()
                    val count=db.rawQuery("SELECT count(*) FROM preferences WHERE key LIKE 'mute:%'",null).use {it.moveToFirst();it.getInt(0)}
                    require(existing||count<1000)
                    db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key);put("value",untilMs.toString())},android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {db.endTransaction()}
        }
    }
}
