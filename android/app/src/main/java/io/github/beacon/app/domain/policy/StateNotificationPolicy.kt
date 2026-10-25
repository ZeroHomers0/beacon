package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.DataStatus
import io.github.beacon.app.domain.model.QuietHours
import io.github.beacon.app.domain.model.QuietHoursBehavior
import java.time.ZoneId

/** Decisions only. Database receipts and Android notification execution are separate. */
object StateNotificationPolicy {
    fun shouldCancel(status: DataStatus, enabled: Boolean, dismissed: Boolean, priority: Int,
        quiet: QuietHours, nowMs: Long, zone: ZoneId): Boolean =
        status in setOf(DataStatus.MISSING,DataStatus.WAITING,DataStatus.CLEARED,DataStatus.EXPIRED) ||
            !enabled || dismissed || priority <= 1 ||
            (quiet.active(nowMs,zone) && quiet.behavior == QuietHoursBehavior.SAVE_ONLY)

    fun shouldAlert(first: Boolean, priority: Int, quiet: QuietHours, nowMs: Long, zone: ZoneId): Boolean =
        first && priority >= 4 && !quiet.active(nowMs,zone)
}
