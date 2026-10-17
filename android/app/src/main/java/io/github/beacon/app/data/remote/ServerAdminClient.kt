package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.TransportSettings
import io.github.beacon.app.domain.model.MessageLimits
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

interface ServerAdministration {
    val endpoint: String

    fun overview(): JSONObject

    fun binding(name: String): JSONObject

    fun clearMessages(): JSONObject = throw UnsupportedOperationException()

    fun deleteDevice(id: String): JSONObject = throw UnsupportedOperationException()

    fun revokeDevice(id: String): JSONObject

    fun test(id: String, title: String, body: String, priority: Int): JSONObject

    fun application(id: String, name: String, priority: Int): JSONObject

    fun revokeApplication(id: String): JSONObject

    fun deleteApplication(id: String): JSONObject = throw UnsupportedOperationException()
}

/** Independent administrator session. Never uses receive credentials or follows redirects. */
class ServerAdminClient(
    endpoint: String,
    private val token: String,
    private val http: OkHttpClient = defaultHttp,
    allowLoopback: Boolean = false,
) : ServerAdministration {
    override val endpoint = endpoint.trim().trimEnd('/')

    init {
        TransportSettings(this.endpoint, token).validate(allowLoopback)
        require(!http.followRedirects && !http.followSslRedirects)
    }

    override fun clearMessages(): JSONObject = request("messages/clear", JSONObject())

    override fun deleteDevice(id: String): JSONObject =
        request("devices/delete", JSONObject().put("device_id", id))

    override fun overview(): JSONObject = request("overview")

    override fun binding(name: String): JSONObject =
        request("binding", JSONObject().put("name", name))

    override fun revokeDevice(id: String): JSONObject =
        request("revoke", JSONObject().put("device_id", id))

    override fun test(id: String, title: String, body: String, priority: Int): JSONObject =
        request(
            "test",
            JSONObject()
                .put("device_id", id)
                .put("title", title)
                .put("body", body)
                .put("priority", priority),
        )

    override fun application(id: String, name: String, priority: Int): JSONObject =
        request(
            "applications",
            JSONObject().put("device_id", id).put("name", name).put("default_priority", priority),
        )

    override fun revokeApplication(id: String): JSONObject =
        request("applications/revoke", JSONObject().put("id", id))

    override fun deleteApplication(id: String): JSONObject =
        request("applications/delete", JSONObject().put("id", id))

    private fun request(path: String, body: JSONObject? = null): JSONObject {
        body?.let { payload ->
            if (payload.has("name")) {
                val name = payload.getString("name").trim()
                if (name.isEmpty() || !MessageLimits.textFits(name, MessageLimits.NAME_BYTES))
                    throw AdminInputException()
            }
            if (path == "test") {
                if (!MessageLimits.textFits(payload.getString("title"), MessageLimits.TITLE_BYTES))
                    throw AdminInputException()
                val text = payload.getString("body")
                if (text.isEmpty() || !MessageLimits.textFits(text, MessageLimits.BODY_BYTES) ||
                    payload.getInt("priority") !in 0..10) throw AdminInputException()
            }
            if (!MessageLimits.textFits(payload.toString(), MessageLimits.REQUEST_BYTES))
                throw AdminInputException()
        }
        val builder =
            Request.Builder()
                .url("$endpoint/api/admin/$path")
                .header("Authorization", "Bearer $token")
                .header("Cache-Control", "no-store")
        if (body != null)
            builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw AdminRequestException(response.code)
            val source = response.body?.source() ?: throw IOException("empty response")
            // A large/broken server response cannot grow the app heap without a bound.
            if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("response too large")
            return JSONObject(source.readUtf8())
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
        // Derived from the shared client so the admin console reuses the same connection pool
        // and dispatcher; only the overall budget differs (a full device list can be slow).
        private val defaultHttp =
            HttpClientFactory.shared.newBuilder()
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
    }
}

class AdminRequestException(val status: Int) : IOException("admin request failed ($status)")

class AdminInputException : IllegalArgumentException("invalid administrator input")
