package io.github.beacon.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Separate from event history and unread/ACK queues; shared safely across app processes. */
class StateCache(private val context: Context) : SQLiteOpenHelper(context, "states.db", null, 3) {
    init { setWriteAheadLoggingEnabled(true) }
    private fun createTopics(db:SQLiteDatabase){db.execSQL("CREATE TABLE topics(source TEXT NOT NULL,identity TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(source,identity))");db.execSQL("CREATE TABLE topic_cursors(source TEXT PRIMARY KEY,cursor TEXT NOT NULL)")}
    override fun onCreate(db: SQLiteDatabase) {
        createTopics(db)
        db.execSQL("CREATE TABLE states(source TEXT NOT NULL,identity TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(source,identity))")
        db.execSQL("CREATE TABLE watermarks(source TEXT NOT NULL,identity TEXT NOT NULL,revision INTEGER NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(source,identity))")
        db.execSQL("CREATE TABLE cursors(source TEXT PRIMARY KEY,cursor TEXT NOT NULL)")
        db.execSQL("CREATE TABLE preferences(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {if(oldVersion<3)createTopics(db);if(oldVersion<2)db.execSQL("CREATE TABLE watermarks(source TEXT NOT NULL,identity TEXT NOT NULL,revision INTEGER NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(source,identity))")}
    fun cursor(source: String): String = readableDatabase.rawQuery("SELECT cursor FROM cursors WHERE source=?", arrayOf(source)).use { if (it.moveToFirst()) it.getString(0) else "" }
    private fun dataRows(): List<JSONObject> = readableDatabase.rawQuery("SELECT source,identity,payload FROM states ORDER BY source,identity", null).use { c ->
        buildList { while (c.moveToNext()) add(JSONObject(c.getString(2)).put("local_source", c.getString(0)).put("local_identity", c.getString(1))) }
    }
    fun preference(key: String): String = readableDatabase.rawQuery("SELECT value FROM preferences WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else "" }
    fun preference(key: String, value: String) {
        if (preference(key) == value) return
        writableDatabase.insertWithOnConflict("preferences", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
        if(key.startsWith("widget:"))io.github.beacon.app.core.TopicChanges.ui(context)
    }
    private fun dataRow(source:String,identity:String):JSONObject?=readableDatabase.rawQuery("SELECT payload FROM states WHERE source=? AND identity=?",arrayOf(source,identity)).use{if(it.moveToFirst())JSONObject(it.getString(0)).put("local_source",source).put("local_identity",identity)else null}
    private fun merge(data:JSONObject?,topic:JSONObject):JSONObject {
        val deleted=topic.optBoolean("deleted")
        val result=if(data!=null&&!deleted)JSONObject(data.toString())else JSONObject().put("topic_key",topic.getString("topic_key")).put("application_id",topic.optString("application_id")).put("revision",0).put("blocks",JSONArray()).put("body",if(deleted)"主题已删除"else"等待数据")
        return result.put("local_source",topic.optString("local_source")).put("local_identity",topic.optString("local_identity"))
            .put("application_name",topic.optString("application_name").ifBlank{data?.optString("application_name").orEmpty()})
            .put("title",topic.optString("name").ifBlank{data?.optString("title").orEmpty()}.ifBlank{topic.optString("topic_key")})
            .put("_waiting",data==null&&!deleted).put("_topic_deleted",deleted).put("config_version",topic.optLong("config_version"))
    }
    fun all():List<JSONObject>{
        val rows=dataRows()
        val catalogSources=readableDatabase.rawQuery("SELECT source FROM topic_cursors",null).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
        val originals=rows.associateBy{it.optString("local_source")+":"+it.optString("local_identity")}
        val data=originals.filterValues{it.optString("local_source") !in catalogSources}.toMutableMap()
        readableDatabase.rawQuery("SELECT source,identity,payload FROM topics ORDER BY source,identity",null).use{c->while(c.moveToNext()){
            val topic=JSONObject(c.getString(2)).put("local_source",c.getString(0)).put("local_identity",c.getString(1));val key=c.getString(0)+":"+c.getString(1)
            data[key]=merge(originals[key],topic)
        }}
        return data.values.toList()
    }
    fun find(source:String,identity:String):JSONObject? {
        val data=dataRow(source,identity)
        return readableDatabase.rawQuery("SELECT payload FROM topics WHERE source=? AND identity=?",arrayOf(source,identity)).use{c->if(c.moveToFirst())merge(data,JSONObject(c.getString(0)).put("local_source",source).put("local_identity",identity))else if(topicCursor(source).isBlank())data else null}
    }
    fun topicCursor(source:String):String=readableDatabase.rawQuery("SELECT cursor FROM topic_cursors WHERE source=?",arrayOf(source)).use{if(it.moveToFirst())it.getString(0)else ""}
    fun applyTopics(source:String,records:List<JSONObject>,cursor:String,replace:Boolean,expectedCursor:String){
        require(records.size<=10000&&cursor.length<=1024)
        val db=writableDatabase;db.beginTransaction();var changed=replace
        try{
            if(topicCursor(source)!=expectedCursor)return
            if(replace){
                db.delete("cursors","source=?",arrayOf(source))
                val identities=records.map{it.optString("application_id")+":"+it.optString("topic_key")}.toSet()
                val removed=readableDatabase.rawQuery("SELECT identity FROM topics WHERE source=?",arrayOf(source)).use{c->buildList{while(c.moveToNext())if(c.getString(0) !in identities)add(c.getString(0))}}
                for(identity in removed)for(table in listOf("states","watermarks"))db.delete(table,"source=? AND identity=?",arrayOf(source,identity))
                db.delete("topics","source=?",arrayOf(source))
            }
            for(row in records){
                val topic=row.getString("topic_key");val app=row.getString("application_id");val version=row.getLong("config_version")
                require(topic.toByteArray().size in 1..128&&(app.isEmpty()||app.matches(Regex("[0-9a-f]{64}")))&&version in 1..9007199254740991L&&row.toString().toByteArray().size<=4096)
                val identity="$app:$topic"
                val previous=db.rawQuery("SELECT payload FROM topics WHERE source=? AND identity=?",arrayOf(source,identity)).use{if(it.moveToFirst())JSONObject(it.getString(0))else null}
                if(previous!=null&&previous.optLong("config_version")>=version)continue
                changed=true
                if(version==1L||row.optBoolean("deleted")){db.delete("cursors","source=?",arrayOf(source));for(table in listOf("states","watermarks"))db.delete(table,"source=? AND identity=?",arrayOf(source,identity))}
                if(row.optBoolean("deleted")){for(table in listOf("topics","states","watermarks"))db.delete(table,"source=? AND identity=?",arrayOf(source,identity));continue}
                db.insertWithOnConflict("topics",null,ContentValues().apply{put("source",source);put("identity",identity);put("payload",row.toString())},SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.rawQuery("SELECT count(*) FROM topics WHERE source=?",arrayOf(source)).use{require(it.moveToFirst()&&it.getInt(0)<=500)}
            db.insertWithOnConflict("topic_cursors",null,ContentValues().apply{put("source",source);put("cursor",cursor)},SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        if(changed)io.github.beacon.app.core.TopicChanges.data(context,source,null)
    }
    fun notificationBindings():List<Pair<String,String>> = readableDatabase.rawQuery("SELECT key FROM preferences WHERE key LIKE 'notify:%' AND value='1'",null).use{cursor->buildList{while(cursor.moveToNext()){val parts=cursor.getString(0).removePrefix("notify:").split(":",limit=2);if(parts.size==2)add(parts[0] to parts[1])}}}
    fun forget(source: String) {val db=writableDatabase;db.beginTransaction();try{for(table in listOf("states","cursors","watermarks","topics","topic_cursors"))db.delete(table,"source=?",arrayOf(source));db.setTransactionSuccessful()}finally{db.endTransaction()};io.github.beacon.app.core.TopicChanges.data(context,source,null)}
    fun apply(source: String, records: List<JSONObject>, cursor: String, replace: Boolean, expectedCursor:String?=null) {
        require(records.size <= 10000 && cursor.length <= 1024)
        val db = writableDatabase; db.beginTransaction()
        val changedIdentities = mutableListOf<String>()
        try {
            if(expectedCursor!=null){val current=db.rawQuery("SELECT cursor FROM cursors WHERE source=?",arrayOf(source)).use{if(it.moveToFirst())it.getString(0)else ""};if(current!=expectedCursor)return}
            if (replace) db.delete("states", "source=?", arrayOf(source))
            for (row in records) {
                val topic = row.getString("topic_key"); val app = row.getString("application_id")
                require(topic.toByteArray().size in 1..128 && (app.isEmpty() || app.matches(Regex("[0-9a-f]{64}"))))
                val identity = "$app:$topic"
                if(topicCursor(source).isNotBlank()&&!db.rawQuery("SELECT 1 FROM topics WHERE source=? AND identity=?",arrayOf(source,identity)).use{it.moveToFirst()})continue
                val revision=row.getLong("revision");require(revision in 1..9007199254740991L)
                val deleted=row.optBoolean("deleted")
                val prior=db.rawQuery("SELECT revision,deleted FROM watermarks WHERE source=? AND identity=?",arrayOf(source,identity)).use{if(it.moveToFirst())it.getLong(0) to (it.getInt(1)==1)else 0L to false}
                if(prior.first>revision||prior.first==revision&&prior.second&&!deleted)continue
                // Revisions are immutable. Replays advance the cursor, not the visible record.
                if(prior.first==revision && (prior.second && deleted || !prior.second && !deleted && dataRow(source,identity)!=null))continue
                changedIdentities.add(identity)
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
        if(replace||changedIdentities.isNotEmpty())io.github.beacon.app.core.TopicChanges.data(context,source,if(replace)null else changedIdentities)
    }
}
