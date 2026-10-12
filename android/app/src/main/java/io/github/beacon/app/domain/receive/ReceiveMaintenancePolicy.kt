package io.github.beacon.app.domain.receive

/** No timer: maintenance is admitted by reception, using monotonic time. */
internal class ReceiveMaintenancePolicy {
    private var lastRun = Long.MIN_VALUE
    private var received = 0

    fun due(nowMs: Long): Boolean {
        received++
        return lastRun == Long.MIN_VALUE || nowMs - lastRun >= 15 * 60_000L || received >= 100
    }

    fun completed(nowMs: Long) {
        lastRun = nowMs
        received = 0
    }
}
