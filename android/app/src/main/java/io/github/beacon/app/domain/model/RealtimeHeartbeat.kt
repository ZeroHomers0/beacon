package io.github.beacon.app.domain.model

/** Keep failure detection bounded; messages are pushed independently of this interval. */
object RealtimeHeartbeat {
    const val DEFAULT_SECONDS = 60
    val options = listOf(30, 60, 90, 120, 180)

    fun validate(seconds: Int) {
        require(seconds in options)
    }
}
