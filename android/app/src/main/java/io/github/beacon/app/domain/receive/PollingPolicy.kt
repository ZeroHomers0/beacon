package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.PollingState
import io.github.beacon.app.domain.model.SyncOutcome

data class SyncReport(val outcome: SyncOutcome, val text: String)

enum class ScheduleAction {
    KEEP,
    ENABLE,
    CANCEL,
}

object PollingSchedulePolicy {
    fun decide(previous: PollingState?, next: PollingState): ScheduleAction =
        when {
            previous != null && next.revision < previous.revision -> ScheduleAction.KEEP
            !next.shouldSchedule -> ScheduleAction.CANCEL
            previous?.shouldSchedule == true && previous.config == next.config ->
                ScheduleAction.KEEP
            else -> ScheduleAction.ENABLE
        }
}

object PollingPolicy {
    fun retry(outcome: SyncOutcome, attempt: Int): Boolean =
        outcome in listOf(SyncOutcome.TRANSIENT, SyncOutcome.STORAGE) && attempt in 0..1

    fun blockingReason(outcome: SyncOutcome): String =
        when (outcome) {
            SyncOutcome.AUTH -> "无法验证设备，请重新绑定服务器"
            SyncOutcome.TLS -> "无法确认连接安全，请检查手机时间或联系管理员"
            SyncOutcome.PROTOCOL -> "服务暂不兼容，请检查地址或更新版本"
            SyncOutcome.CONFIG -> "连接配置不可用，请重新保存配置"
            else -> ""
        }
}
