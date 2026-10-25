package io.github.beacon.app.widget

import org.json.JSONObject

/** Counters contain no state content or credentials. Debuggable via the management page. */
object WidgetPerformance {
    val requests=java.util.concurrent.atomic.AtomicLong()
    val renders=java.util.concurrent.atomic.AtomicLong()
    val reused=java.util.concurrent.atomic.AtomicLong()
    val skipped=java.util.concurrent.atomic.AtomicLong()
    val pixels=java.util.concurrent.atomic.AtomicLong()
    val observers=java.util.concurrent.atomic.AtomicLong()
    fun summary()="本进程：刷新 ${requests.get()} · 绘制 ${renders.get()} · 复用 ${reused.get()} · 跳过 ${skipped.get()}"
    fun interval(config:JSONObject,row:JSONObject?):Long=when(config.optString("refresh","balanced")){
        "fast"->5000;"economy"->60000;else->if(row?.optString("state_type")=="task")5000 else 15000
    }
    fun displayKey(row:JSONObject?,inputConfig:JSONObject,now:Long=System.currentTimeMillis()):String {
        val config=WidgetConfigRules.resolved(row,inputConfig)
        val appearance=JSONObject(config.toString()).apply{listOf("source","identity","instance_name","refresh").forEach{remove(it)}}
        val stale=row!=null&&row.optLong("stale_at_ms") in 1..now
        val expired=row!=null&&row.optLong("expires_at_ms") in 1..now
        val display=JSONObject().put("title",config.optString("title").ifBlank{row?.optString("title").orEmpty()}).put("status",row?.optString("status")).put("waiting",row?.optBoolean("_waiting")==true).put("topic_deleted",row?.optBoolean("_topic_deleted")==true).put("missing",row==null).put("stale",stale).put("expired",expired)
        if(row!=null&&!expired){
            if(config.optString("template")!="text")display.put("blocks",org.json.JSONArray(WidgetConfigRules.selected(row,config)))
            if(config.optString("template")=="text")display.put("body",row.optString("body"))
            if(stale)display.put("sampleMinute",row.optLong("observed_at_ms")/60000)
            else if(config.optBoolean("updated",true))display.put("updatedMinute",row.optLong("accepted_at_ms")/60000)
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest((canonical(appearance)+canonical(display)).toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
    }
    private fun canonical(value:Any?):String=when(value){
        is JSONObject->value.keys().asSequence().toList().sorted().joinToString(prefix="{",postfix="}"){JSONObject.quote(it)+":"+canonical(value.opt(it))}
        is org.json.JSONArray->(0 until value.length()).joinToString(prefix="[",postfix="]"){canonical(value.opt(it))}
        is String->JSONObject.quote(value)
        null,JSONObject.NULL->"null"
        else->value.toString()
    }
}
