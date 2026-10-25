package io.github.beacon.app.widget

import org.json.JSONObject

/** Minute-granularity display; desktop updates are non-wakeup and only while interactive. */
object WidgetCountdown {
    fun caption(target:Long,now:Long):String {
        if(target<0)return "暂无时间"
        val minutes=((target-now).coerceAtLeast(0)+59_999)/60_000
        return when {
            minutes==0L->"已到时间"
            minutes>=1440->"${minutes/1440}天 ${minutes%1440/60}小时"
            minutes>=60->"${minutes/60}小时 ${minutes%60}分"
            else->"${minutes}分钟"
        }
    }
    fun next(row:JSONObject?,config:JSONObject,now:Long,interactive:Boolean=true):Long? {
        if(!interactive||row==null||row.optBoolean("_waiting")||row.optLong("expires_at_ms") in 1..now||row.optLong("stale_at_ms") in 1..now)return null
        val resolved=WidgetConfigRules.resolved(row,config)
        if(resolved.optString("template")!="countdown")return null
        val target=WidgetConfigRules.selected(row,resolved).firstOrNull()?.optLong("target_at_ms")?:return null
        if(target<=now)return null
        val delta=target-now
        return now+(delta%60_000).let{if(it==0L)60_000L else it}
    }
}
