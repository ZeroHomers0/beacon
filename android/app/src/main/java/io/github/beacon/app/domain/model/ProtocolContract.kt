package io.github.beacon.app.domain.model

/**
 * Wire contract versions the client understands.
 *
 * The server advertises these as integers in `/healthz`, `/capabilities` and
 * `/bind`. Both sides accept a *range*, never an exact value: a server that
 * bumps its version must not brick installs that predate the bump, and a newer
 * client must still talk to an older server. Field-level differences stay
 * discoverable through the capability payload itself.
 *
 * Kept in one object so a protocol bump is a single-line change on the client.
 */
object ProtocolContract {
    /** Oldest HTTP contract this build still speaks. */
    const val MIN_HTTP = 1

    /** Newest HTTP contract this build understands; newer servers must degrade. */
    const val MAX_HTTP = 1

    /** `/api/v1/realtime/config` contract; it is versioned separately from HTTP. */
    const val MIN_REALTIME = 2
    const val MAX_REALTIME = 2

    /** Envelope version of a streamed message frame. */
    const val MIN_REALTIME_ENVELOPE = 1
    const val MAX_REALTIME_ENVELOPE = 1

    /** Meaning of `delivery_window_ms` / `receipt_window_ms`. */
    const val MIN_DELIVERY_RETENTION = 1
    const val MAX_DELIVERY_RETENTION = 1

    /** `/api/admin/overview` contract used by the in-app server console. */
    const val MIN_ADMIN = 1
    const val MAX_ADMIN = 1

    fun httpSupported(value: Int): Boolean = value in MIN_HTTP..MAX_HTTP

    fun realtimeSupported(value: Int): Boolean = value in MIN_REALTIME..MAX_REALTIME

    fun realtimeEnvelopeSupported(value: Int): Boolean =
        value in MIN_REALTIME_ENVELOPE..MAX_REALTIME_ENVELOPE

    fun deliveryRetentionSupported(value: Int): Boolean =
        value in MIN_DELIVERY_RETENTION..MAX_DELIVERY_RETENTION

    fun adminSupported(value: Int): Boolean = value in MIN_ADMIN..MAX_ADMIN

    /**
     * Page size to request. A server advertising a smaller `max_page_size` wins;
     * an absent or nonsensical value keeps the client's conservative default.
     */
    fun pageSize(advertised: Int?): Int {
        val default = HttpLimits.PAGE_SIZE
        if (advertised == null || advertised < 1) return default
        return minOf(default, advertised)
    }
}

/** Client-side request bounds, mirroring the server contract without depending on it. */
object HttpLimits {
    /**
     * Rows requested per page. The server's `max_page_size` still wins when it is smaller
     * ([ProtocolContract.pageSize]), and a page of this size stays well inside one sync budget
     * while a catch-up no longer needs a round trip every ten messages.
     */
    const val PAGE_SIZE = 25

    /** Safety net when a server reports itself unable to say how large a page may be. */
    const val MAX_PAGES_PER_PATH = 10

    const val ACK_BATCH_SIZE = 100
}
