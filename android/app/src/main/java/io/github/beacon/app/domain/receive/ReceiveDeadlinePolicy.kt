package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.ReceiveMode
import io.github.beacon.app.domain.model.ReceivePlan
import java.time.ZonedDateTime

/** Only a configuration retry or a rule boundary needs a timer; socket health owns its ping. */
object ReceiveDeadlinePolicy {
    /**
     * Used only when the default-network callback could not be registered and the device is
     * offline. The callback is normally the wake source that ends an offline wait, so this exists
     * to keep a failed registration from stranding reception rather than to poll the network.
     */
    const val OFFLINE_FALLBACK_MS = 15 * 60_000L

    fun delayMs(plan: ReceivePlan, now: ZonedDateTime, retryAtMs: Long): Long? {
        val nowMs = now.toInstant().toEpochMilli()
        val deadlines = mutableListOf<Long>()
        if (retryAtMs > nowMs) deadlines.add(retryAtMs)
        if (plan.mode == ReceiveMode.RULES && plan.scheduleEnabled) {
            for (minute in listOf(plan.scheduleStart, plan.scheduleEnd)) {
                for (day in 0L..1L) {
                    val local = now.toLocalDate().plusDays(day).atTime(minute / 60, minute % 60)
                    val offsets = now.zone.rules.getValidOffsets(local)
                    val boundaries =
                        if (offsets.isEmpty()) {
                            listOf(local.atZone(now.zone).toInstant().toEpochMilli())
                        } else {
                            offsets.map { local.toInstant(it).toEpochMilli() }
                        }
                    deadlines.addAll(boundaries.filter { it > nowMs })
                }
            }
            // Recompute after a timezone offset change, including a skipped/repeated local hour.
            now.zone.rules.nextTransition(now.toInstant())?.instant?.toEpochMilli()?.let {
                deadlines.add(it)
            }
        }
        return deadlines.minOrNull()?.let { (it - nowMs).coerceAtLeast(1) }
    }
}
