package io.github.beacon.app.domain.receive

/** Runtime-actor state. All times are monotonic; realtime receipts are not HTTP reconciliations. */
class ReceiveNetworkWorkPolicy {
    private var successfulGeneration: Long? = null
    private var successfulAtMs = 0L
    private var retryGeneration: Long? = null
    private var retryAtMs = 0L

    fun completed(generation: Long, nowMs: Long) {
        successfulGeneration = generation
        successfulAtMs = nowMs
        retryGeneration = null
    }

    fun defer(generation: Long, nowMs: Long, delayMs: Long) {
        retryGeneration = generation
        retryAtMs = nowMs + delayMs.coerceIn(0, RetryAfterPolicy.MAX_DELAY_MS)
    }

    fun waiting(generation: Long, nowMs: Long): Boolean =
        retryGeneration == generation && nowMs < retryAtMs

    fun skipPeriodicSync(generation: Long, nowMs: Long, connected: Boolean): Boolean =
        connected &&
            successfulGeneration == generation &&
            nowMs - successfulAtMs in 0 until RECONCILE_INTERVAL_MS

    companion object {
        // Preserve a bounded HTTP reconciliation for retention and missed messages, even online.
        const val RECONCILE_INTERVAL_MS = 60 * 60_000L
        const val RECEIPT_BATCH_DELAY_MS = 1_000L
    }
}
