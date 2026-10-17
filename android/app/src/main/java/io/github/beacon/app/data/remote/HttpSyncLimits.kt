package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.HttpLimits

/**
 * One bounded session: up to [MAX_PAGES_PER_PATH] pages of [PAGE_SIZE] rows per path (pending
 * replay and incremental read each), all inside [RUN_BUDGET_MS].
 */
internal object HttpSyncLimits {
    const val PAGE_SIZE = HttpLimits.PAGE_SIZE
    const val MAX_PAGES_PER_PATH = HttpLimits.MAX_PAGES_PER_PATH
    const val ACK_BATCH_SIZE = HttpLimits.ACK_BATCH_SIZE

    // A full page of maximally escaped bodies plus bounded Bark metadata and the envelope.
    // One page is the largest response the decoder accepts, so this scales with PAGE_SIZE.
    const val MESSAGE_RESPONSE_BYTES = 12 * 1024 * 1024
    const val CONTROL_RESPONSE_BYTES = 64 * 1024
    const val RUN_BUDGET_MS = 30000L
    const val SOCKET_TIMEOUT_MS = 5000
}
