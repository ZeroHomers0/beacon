package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.TransportSettings
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

    fun test(id: String, title: String, body: String): JSONObject

    fun application(id: String, name: String, priority: Int): JSONObject

    fun revokeApplication(id: String): JSONObject
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

    override fun deleteDevice(id: String): JSONObject = request("devices/delete", JSONObject().put("device_id", id))

    override fun overview(): JSONObject = request("overview")

    override fun binding(name: String): JSONObject =
        request("binding", JSONObject().put("name", name))

    override fun revokeDevice(id: String): JSONObject =
        request("revoke", JSONObject().put("device_id", id))

    override fun test(id: String, title: String, body: String): JSONObject =
        request("test", JSONObject().put("device_id", id).put("title", title).put("body", body))

    override fun application(id: String, name: String, priority: Int): JSONObject =
        request(
            "applications",
            JSONObject().put("device_id", id).put("name", name).put("default_priority", priority),
        )

    override fun revokeApplication(id: String): JSONObject =
        request("applications/revoke", JSONObject().put("id", id))

    private fun request(path: String, body: JSONObject? = null): JSONObject {
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
        private val defaultHttp =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
    }
}

class AdminRequestException(val status: Int) : IOException("admin request failed ($status)")
