package io.github.beacon.app.domain.model

data class PollingConfig(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 15,
    val unmetered: Boolean = false,
    val charging: Boolean = false,
) {
    fun validate() {
        require(intervalMinutes in 15..1440)
    }
}

data class PollingState(
    val config: PollingConfig = PollingConfig(),
    val blocked: String = "",
    val lastAttemptMs: Long = 0,
    val lastSuccessMs: Long = 0,
    val lastResult: String = "尚未自动同步",
    val revision: Long = 0,
    val lastOutcome: SyncOutcome? = null,
) {
    val shouldSchedule: Boolean
        get() = config.enabled && blocked.isEmpty()
}

enum class SyncOutcome(val wireId: Int) {
    OK(0),
    STOPPED(1),
    TRANSIENT(2),
    AUTH(3),
    TLS(4),
    PROTOCOL(5),
    STORAGE(6),
    CONFIG(7),
    BUSY(8);

    companion object {
        fun fromWireId(id: Int): SyncOutcome? = entries.firstOrNull { it.wireId == id }
    }
}
