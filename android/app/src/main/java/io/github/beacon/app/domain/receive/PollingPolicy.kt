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
            SyncOutcome.AUTH -> "认证失败，请修复接收凭证"
            SyncOutcome.TLS -> "证书校验失败，请修复服务器证书"
            SyncOutcome.PROTOCOL -> "服务协议不兼容，请检查地址或版本"
            SyncOutcome.CONFIG -> "连接配置不可用，请重新保存配置"
            else -> ""
        }
}
