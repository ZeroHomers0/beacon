package io.github.beacon.app.data.remote

/** One bounded session: at most 100 pending and 100 incremental rows, within 30 seconds. */
internal object HttpSyncLimits {
    const val PAGE_SIZE = 10
    const val MAX_PAGES_PER_PATH = 10
    const val ACK_BATCH_SIZE = 100
    // Ten maximally escaped bodies plus bounded Bark metadata and the protocol envelope.
    const val MESSAGE_RESPONSE_BYTES = 12 * 1024 * 1024
    const val CONTROL_RESPONSE_BYTES = 64 * 1024
    const val RUN_BUDGET_MS = 30000L
    const val SOCKET_TIMEOUT_MS = 5000
}
