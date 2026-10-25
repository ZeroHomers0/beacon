package io.github.beacon.app.domain.model

import java.time.Instant
import java.time.ZoneId

enum class MuteKind { GLOBAL, APPLICATION, TOPIC }
data class MuteScope(val kind: MuteKind, val source: String = "", val identity: String = "") {
    fun validate() {
        require(source.length <= 512 && identity.length <= 512)
        require(source.none { it.isISOControl() } && identity.none { it.isISOControl() })
        if(kind==MuteKind.GLOBAL) require(source.isEmpty()&&identity.isEmpty())
        else require(source.isNotBlank()&&identity.isNotBlank())
    }
    companion object { val GLOBAL = MuteScope(MuteKind.GLOBAL) }
}

data class MuteSnapshot(val localUntilMs: Long = 0, val globalUntilMs: Long = 0)

object TemporaryMute {
    fun until(hours: Int, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long = when(hours) {
        0 -> 0
        1, 8 -> Math.addExact(nowMs,hours*3_600_000L)
        -1 -> Instant.ofEpochMilli(nowMs).atZone(zone).let { now ->
            val morning=now.toLocalDate().atTime(8,0).atZone(zone)
            (if(morning.isAfter(now))morning else now.toLocalDate().plusDays(1).atTime(8,0).atZone(zone)).toInstant().toEpochMilli()
        }
        else -> throw IllegalArgumentException("Unsupported mute duration")
    }
    fun active(untilMs: Long, nowMs: Long): Boolean = untilMs > nowMs
}

class MuteRules(private val rules: Map<MuteScope,Long>) {
    fun activeScopes(nowMs:Long):List<MuteScope> = rules.filterValues{TemporaryMute.active(it,nowMs)}.keys.toList()
    fun muted(source: String, application: String = "", topic: String = "", nowMs: Long): Boolean =
        listOfNotNull(MuteScope.GLOBAL,
            if(source.isNotBlank()&&application.isNotBlank())MuteScope(MuteKind.APPLICATION,source,application)else null,
            if(source.isNotBlank()&&topic.isNotBlank())MuteScope(MuteKind.TOPIC,source,topic)else null)
            .any { TemporaryMute.active(rules[it]?:0,nowMs) }
}
