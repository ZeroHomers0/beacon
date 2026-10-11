package io.github.beacon.app.domain.model

/** Stable event identifiers. No arbitrary text or message identity is accepted. */
enum class RuntimeEventKind(val wireId: Int) {
    SERVICE_STARTED(1),
    MANUAL_SYNC(2),
    AUTO_SYNC(3),
    REALTIME_CONNECTING(4),
    REALTIME_CONNECTED(5),
    REALTIME_RETRYING(6),
    REALTIME_STOPPED(7),
    REALTIME_CONFIG_FAILED(8),
    NETWORK_CHANGED(9),
    MODE_SAVED(10),
    RECEIVE_ENABLED(11),
    RECEIVE_PAUSED(12),
    CONNECTION_CLEARED(13),
    RECONNECT_REQUESTED(14),
    REALTIME_REJECTED(15),
    WAIT_NETWORK(16),
    WAIT_RULES(17),
    CONNECTION_SAVED(18),
    CREDENTIALS_ROTATED(19),
    DEVICE_REVOKED(20),

    /** A locked-boot event arrived and encrypted storage was still unreadable. */
    RECOVERY_AWAITING_UNLOCK(21),

    /** The system restarted the receive process after it was killed. */
    UNEXPECTED_EXIT_RECOVERY(22),

    /** The bounded unexpected-exit budget ran out; automatic recovery stopped. */
    RECOVERY_SUSPENDED(23);

    companion object {
        fun fromWireId(id: Int) = entries.firstOrNull { it.wireId == id }
    }
}

data class RuntimeEvent(
    val atMs: Long,
    val kind: RuntimeEventKind,
    val outcome: SyncOutcome? = null,
    val failure: RealtimeFailure = RealtimeFailure.NONE,
) {
    fun validate() {
        require(atMs > 0)
    }

    companion object {
        const val MAX_EVENTS = 100
    }
}

/** Preserve insertion order even if the device clock moves backwards. */
object RuntimeEventHistory {
    fun append(history: List<RuntimeEvent>, event: RuntimeEvent): List<RuntimeEvent> {
        event.validate()
        return (history.takeLast(RuntimeEvent.MAX_EVENTS - 1) + event)
    }
}
