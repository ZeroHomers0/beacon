package io.github.beacon.app.data.remote

import org.json.JSONObject

internal object MessagePriorityDecoder {
    fun decode(row: JSONObject): Int {
        if (!row.has("priority")) return 5
        val value = row.opt("priority")
        require(value is Int && value in 0..10)
        return value
    }
}
