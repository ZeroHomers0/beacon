package io.github.beacon.app.data.remote

import org.json.JSONObject

object DeliveryRetentionDecoder {
    /** 10-minute margin covers the bounded timestamp normalization used by both transports. */
    fun cutoff(reply: JSONObject): Long? {
        if (!reply.has("delivery_retention_protocol")) return null
        require(reply.opt("delivery_retention_protocol") == 1)
        fun number(key: String): Long {
            val value = reply.get(key)
            require(value is Int || value is Long)
            return (value as Number).toLong()
        }
        val delivery = number("delivery_window_ms")
        val receipts = number("receipt_window_ms")
        val serverTime = number("server_time_ms")
        require(delivery in 86400000L..31536000000L && receipts in 86400000L..31536000000L)
        return (serverTime - delivery - receipts - 600000L).takeIf { it > 0 }
    }
}
