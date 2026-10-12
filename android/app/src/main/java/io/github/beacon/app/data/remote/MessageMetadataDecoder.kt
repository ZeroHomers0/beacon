package io.github.beacon.app.data.remote

import org.json.JSONObject

internal object MessageMetadataDecoder {
    fun application(row: JSONObject): String {
        val value = row.opt("application_id") ?: ""
        require(value is String && (value.isEmpty() || MessageIdentity.valid(value)))
        return value
    }

    fun group(row: JSONObject): String {
        val value = row.opt("group") ?: ""
        require(value is String && value.toByteArray(Charsets.UTF_8).size <= 128)
        return value
    }

    fun name(row: JSONObject): String {
        val value = row.opt("application_name") ?: ""
        require(value is String && value.toByteArray(Charsets.UTF_8).size <= 128)
        return value
    }

    fun expires(row: JSONObject): Long {
        val value = row.opt("expires_at_ms") ?: 0L
        require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it >= 0) }
    }
}
