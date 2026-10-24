package io.github.beacon.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Separate from event history and unread/ACK queues; shared safely across app processes. */
class StateCache(context: Context) : SQLiteOpenHelper(context, "states.db", null, 2) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE states(source TEXT NOT NULL,identity TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(source,identity))")
        db.execSQL("CREATE TABLE watermarks(source TEXT NOT NULL,identity TEXT NOT NULL,revision INTEGER NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(source,identity))")
        db.execSQL("CREATE TABLE cursors(source TEXT PRIMARY KEY,cursor TEXT NOT NULL)")
        db.execSQL("CREATE TABLE preferences(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {if(oldVersion<2)db.execSQL("CREATE TABLE watermarks(source TEXT NOT NULL,identity TEXT NOT NULL,revision INTEGER NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(source,identity))")}
    fun cursor(source: String): String = readableDatabase.rawQuery("SELECT cursor FROM cursors WHERE source=?", arrayOf(source)).use { if (it.moveToFirst()) it.getString(0) else "" }
    fun all(): List<JSONObject> = readableDatabase.rawQuery("SELECT source,identity,payload FROM states ORDER BY source,identity", null).use { c ->
        buildList { while (c.moveToNext()) add(JSONObject(c.getString(2)).put("local_source", c.getString(0)).put("local_identity", c.getString(1))) }
    }
    fun preference(key: String): String = readableDatabase.rawQuery("SELECT value FROM preferences WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else "" }
    fun preference(key: String, value: String) { writableDatabase.insertWithOnConflict("preferences", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun forget(source: String) {val db=writableDatabase;db.beginTransaction();try{for(table in listOf("states","cursors","watermarks"))db.delete(table,"source=?",arrayOf(source));db.setTransactionSuccessful()}finally{db.endTransaction()}}
    fun apply(source: String, records: List<JSONObject>, cursor: String, replace: Boolean, expectedCursor:String?=null) {
        require(records.size <= 10000 && cursor.length <= 1024)
        val db = writableDatabase; db.beginTransaction()
        try {
            if(expectedCursor!=null){val current=db.rawQuery("SELECT cursor FROM cursors WHERE source=?",arrayOf(source)).use{if(it.moveToFirst())it.getString(0)else ""};if(current!=expectedCursor)return}
            if (replace) db.delete("states", "source=?", arrayOf(source))
            for (row in records) {
                val topic = row.getString("topic_key"); val app = row.getString("application_id")
                require(topic.toByteArray().size in 1..128 && (app.isEmpty() || app.matches(Regex("[0-9a-f]{64}"))))
                val identity = "$app:$topic"
                val revision=row.getLong("revision");require(revision in 1..9007199254740991L)
                val deleted=row.optBoolean("deleted")
                val prior=db.rawQuery("SELECT revision,deleted FROM watermarks WHERE source=? AND identity=?",arrayOf(source,identity)).use{if(it.moveToFirst())it.getLong(0) to (it.getInt(1)==1)else 0L to false}
                if(prior.first>revision||prior.first==revision&&prior.second&&!deleted)continue
                db.insertWithOnConflict("watermarks",null,ContentValues().apply{put("source",source);put("identity",identity);put("revision",revision);put("deleted",if(deleted)1 else 0)},SQLiteDatabase.CONFLICT_REPLACE)
                if(deleted){db.delete("states","source=? AND identity=?",arrayOf(source,identity));continue}
                require(row.getString("body").toByteArray().size <= 65536 && row.toString().toByteArray().size <= 524288)
                row.put("blocks", JSONArray(io.github.beacon.app.data.remote.StructuredBlocksCodec.decode(row)))
                db.insertWithOnConflict("states",null,ContentValues().apply { put("source",source);put("identity",identity);put("payload",row.toString()) },SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.rawQuery("SELECT count(*),coalesce(sum(length(CAST(payload AS BLOB))),0) FROM states WHERE source=?",arrayOf(source)).use{require(it.moveToFirst()&&it.getInt(0)<=500&&it.getLong(1)<=8*1024*1024)}
            db.insertWithOnConflict("cursors",null,ContentValues().apply { put("source",source);put("cursor",cursor) },SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
