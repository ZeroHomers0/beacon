package io.github.beacon.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.beacon.app.R
import io.github.beacon.app.data.remote.AdminRequestException
import io.github.beacon.app.data.remote.ServerAdminClient
import io.github.beacon.app.data.remote.ServerAdministration
import io.github.beacon.app.domain.model.ProtocolContract
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class AdminDevice(val id: String, val name: String, val revoked: Boolean)

data class AdminApplication(
    val id: String,
    val deviceId: String,
    val name: String,
    val priority: Int,
    val revoked: Boolean,
)

data class AdminSecret(
    val label: Int,
    val value: String,
    val expiresAt: Long = 0,
    val example: String = "",
)

/**
 * A mutation a confirmation dialog is waiting for.
 *
 * The dialog keeps itself open until [AdminUiState.awaiting] clears, so a failure can be shown in
 * place instead of dismissing the dialog and leaving the explanation to a snackbar.
 */
enum class AdminAction {
    CLEAR_MESSAGES,
    DELETE_DEVICE,
    REVOKE_DEVICE,
    DELETE_APPLICATION,
    REVOKE_APPLICATION,
    CLEAR_STATE,
}

data class AdminUiState(
    val restoring: Boolean = false,
    val restoreFailure: Int? = null,
    val overviewLoaded: Boolean = false,
    val endpoint: String = "",
    val loggedIn: Boolean = false,
    val busy: Boolean = false,
    val feedback: Int? = null,
    val refreshFailure: Int? = null,
    val awaiting: AdminAction? = null,
    val actionFailure: Int? = null,
    val version: String = "—",
    val protocol: Int = 0,
    val serverId: String = "",
    val messages: Long = 0,
    val pending: Long = 0,
    val databaseBytes: Long = 0,
    val devices: List<AdminDevice> = emptyList(),
    val applications: List<AdminApplication> = emptyList(),
    val secret: AdminSecret? = null,
    val testDeviceId: String = "",
    val topics:List<JSONObject> = emptyList(),
    val topicsTotal:Int = 0,
    val topicsHasMore:Boolean = false,
    val topicsQuery:String = "",
    val topicsLoaded:Boolean = false,
    val topicsFailure:Int? = null,
    val topicDetail:JSONObject? = null,
) {
    fun listState(empty: Boolean) = io.github.beacon.app.domain.model.AsyncPageState.resolve(
        loaded = overviewLoaded,
        loading = busy,
        failed = refreshFailure != null,
        empty = empty,
    )
}

/** Administrator credentials use an independent Keystore; never saved state or settings exports. */
class ServerAdminViewModel(private val createClient: (String, String) -> ServerAdministration) :
    ViewModel() {
    constructor() : this({ endpoint, token -> ServerAdminClient(endpoint, token) })

    internal val listFilters = AdminListFilterState()
    private val mutable = MutableStateFlow(AdminUiState(restoring = true))
    val state = mutable.asStateFlow()
    private val mutableCreating = MutableStateFlow(false)
    val creating = mutableCreating.asStateFlow()

    fun showCreation() {
        if (mutable.value.loggedIn && !mutable.value.busy) mutableCreating.value = true
    }

    fun dismissCreation() {
        mutableCreating.value = false
    }

    fun selectTestDevice(id: String) {
        mutable.value = mutable.value.copy(testDeviceId = id)
    }

    private var session: ServerAdministration? = null
    private var job: Job? = null
    private var generation = 0

    private var credentialStore: io.github.beacon.app.data.local.TransportSecretStore? = null
    private val credentialMutex = Mutex()
    private var restoreJob: Job? = null
    private var managementRequested = false
    private var overviewRequested = false

    fun restoreSession(context: android.content.Context) {
        if (restoreJob?.isActive == true || (credentialStore != null && mutable.value.restoreFailure == null)) return
        val store =
            credentialStore ?:
            io.github.beacon.app.data.local.TransportSecretStore(
                context.applicationContext,
                "admin_secret",
                "beacon_admin_v1",
            )
        credentialStore = store
        val restoreGeneration = generation
        mutable.value = mutable.value.copy(restoring = true, restoreFailure = null)
        restoreJob = viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                credentialMutex.withLock {
                    runCatching { if (store.isConfigured()) store.load() else null }
                }
            }
            if (generation != restoreGeneration || session != null || mutable.value.busy)
                return@launch
            val saved = restored.getOrNull()
            val client = saved?.let { runCatching { createClient(it.endpoint, it.token) }.getOrNull() }
            session = client
            mutable.value =
                mutable.value.copy(
                    restoring = false,
                    endpoint = client?.endpoint.orEmpty(),
                    loggedIn = client != null,
                    restoreFailure = if (restored.isFailure || (saved != null && client == null))
                        R.string.admin_restore_failed else null,
                )
            if (client != null && managementRequested) loadOnEntry()
        }
    }

    fun loadOnEntry() {
        managementRequested = true
        if (mutable.value.loggedIn && !mutable.value.overviewLoaded && !overviewRequested) {
            overviewRequested = true
            refresh()
        }
    }

    private suspend fun persistCredentials(work: () -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            credentialMutex.withLock { runCatching(work).isSuccess }
        }

    /** The caller shows the outcome in the app-wide snackbar, so the message is consumed once. */
    fun dismissFeedback() {
        if (mutable.value.feedback != null) mutable.value = mutable.value.copy(feedback = null)
    }

    fun logout() {
        dismissCreation()
        generation++
        job?.cancel()
        restoreJob?.cancel()
        session = null
        overviewRequested = false
        mutable.value = AdminUiState(busy = true)
        val captured = generation
        job = viewModelScope.launch {
            val cleared = persistCredentials { credentialStore?.clear() }
            if (captured == generation) mutable.value = AdminUiState(
                feedback = if (cleared) R.string.admin_logged_out else R.string.admin_credentials_clear_failed,
                restoreFailure = if (cleared) null else R.string.admin_credentials_clear_failed,
            )
        }
    }

    fun login(endpoint: String, token: String) {
        if (mutable.value.busy) return
        val client =
            try {
                createClient(endpoint, token.trim())
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(feedback = R.string.admin_invalid_login)
                return
            }
        session = client
        run(
            R.string.admin_connected,
            onSuccess = {
                val saved = persistCredentials {
                    credentialStore?.save(
                        io.github.beacon.app.domain.model.TransportSettings(
                            client.endpoint,
                            token.trim(),
                        )
                    )
                }
                if (!saved) mutable.value = mutable.value.copy(feedback = R.string.admin_credentials_save_failed)
            },
        ) {
            val overview = client.overview()
            val result = parseOverview(overview).copy(endpoint = client.endpoint, loggedIn = true)
            withTopics(client,result)
        }
    }

    fun refresh() {
        if (mutable.value.secret != null || mutableCreating.value) return
        authenticated(null) { client ->
            withTopics(client,parseOverview(client.overview())
                .copy(
                    endpoint = client.endpoint,
                    loggedIn = true,
                    testDeviceId = mutable.value.testDeviceId,
                ))
        }
    }

    private fun topicsResult(client:ServerAdministration,query:JSONObject,base:AdminUiState=mutable.value):AdminUiState {
        val result=client.stateTopics(query);val rows=result.getJSONArray("topics")
        return base.copy(topics=List(rows.length()){rows.getJSONObject(it)},topicsTotal=result.getInt("total"),topicsHasMore=result.optBoolean("has_more"),topicsQuery=query.toString(),topicsLoaded=true,topicsFailure=null)
    }
    private fun withTopics(client:ServerAdministration,base:AdminUiState):AdminUiState {
        val query=runCatching{JSONObject(mutable.value.topicsQuery)}.getOrElse{JSONObject().put("device_id","").put("state","all").put("search","").put("limit",50).put("offset",0)}
        return try{topicsResult(client,query,base)}catch(error:Exception){
            if(error is CancellationException||error is AdminRequestException&&error.status in listOf(401,403))throw error
            base.copy(topics=mutable.value.topics,topicsTotal=mutable.value.topicsTotal,topicsHasMore=mutable.value.topicsHasMore,topicsQuery=query.toString(),topicsLoaded=true,topicsFailure=if(error is AdminRequestException&&error.status==404)R.string.admin_unavailable else R.string.admin_request_failed)
        }
    }
    fun loadTopics(query:JSONObject)=authenticated(null){client->topicsResult(client,query)}
    fun loadTopic(identity:JSONObject)=authenticated(null){client->mutable.value.copy(topicDetail=client.stateTopic(identity).getJSONObject("state"))}
    fun dismissTopic(){mutable.value=mutable.value.copy(topicDetail=null)}
    fun clearTopic(identity:JSONObject,query:JSONObject,onSuccess:()->Unit){
        val client=session?:return
        run(R.string.admin_topic_cleared,AdminAction.CLEAR_STATE,onSuccess={onSuccess()}){client.clearStateTopic(identity);topicsResult(client,query).copy(topicDetail=null,awaiting=null,actionFailure=null)}
    }

    fun createBinding(name: String) =
        authenticated(R.string.admin_binding_created) { client ->
            val result = client.binding(name.trim())
            mutable.value.copy(
                secret =
                    AdminSecret(
                        R.string.admin_binding_code,
                        result.getString("binding_code"),
                        result.getLong("expires_at_ms"),
                    )
            )
        }

    fun bindThisPhone(name: String, onBindingReady: (String, String, String) -> Unit) {
        val client = session ?: return
        var code = ""
        run(
            R.string.admin_binding_started,
            onSuccess = {
                onBindingReady(client.endpoint, code, name)
            },
        ) {
            code = client.binding(name).getString("binding_code")
            mutable.value
        }
    }

    fun createApplication(device: String, name: String, priority: Int) =
        authenticated(R.string.admin_application_created) { client ->
            val result = client.application(device, name.trim(), priority)
            val token = result.getString("token")
            val example =
                "curl -X POST '${client.endpoint}/api/v1/push' " +
                    "-H 'Authorization: Bearer $token' -H 'Content-Type: application/json' " +
                    "-d '{\"title\":\"Beacon\",\"body\":\"Hello\"}'"
            // Update the list locally so a failed follow-up request cannot hide the one-time
            // secret.
            mutable.value.copy(
                secret =
                    AdminSecret(
                        R.string.admin_application_token,
                        token,
                        example = example,
                    ),
                applications =
                    mutable.value.applications +
                        AdminApplication(
                            result.getString("id"),
                            device,
                            result.getString("name"),
                            priority,
                            false,
                        ),
            )
        }

    fun clearMessages() =
        authenticated(R.string.admin_messages_cleared, AdminAction.CLEAR_MESSAGES) { client ->
            client.clearMessages()
            parseOverview(client.overview()).copy(endpoint = client.endpoint, loggedIn = true)
        }

    fun deleteDevice(id: String) =
        authenticated(R.string.admin_device_deleted, AdminAction.DELETE_DEVICE) { client ->
            client.deleteDevice(id)
            parseOverview(client.overview()).copy(endpoint = client.endpoint, loggedIn = true)
        }

    fun revokeDevice(id: String) =
        authenticated(R.string.admin_device_revoked, AdminAction.REVOKE_DEVICE) { client ->
            client.revokeDevice(id)
            mutable.value.copy(
                devices =
                    mutable.value.devices.map {
                        if (it.id == id) it.copy(revoked = true) else it
                    },
                applications =
                    mutable.value.applications.map {
                        if (it.deviceId == id) it.copy(revoked = true) else it
                    },
            )
        }

    fun revokeApplication(id: String) =
        authenticated(R.string.admin_application_revoked, AdminAction.REVOKE_APPLICATION) { client ->
            client.revokeApplication(id)
            mutable.value.copy(
                applications =
                    mutable.value.applications.map {
                        if (it.id == id) it.copy(revoked = true) else it
                    }
            )
        }

    fun deleteApplication(id: String) =
        authenticated(R.string.admin_application_deleted, AdminAction.DELETE_APPLICATION) { client ->
            client.deleteApplication(id)
            // The application's messages are gone, so the overview counts are stale too.
            parseOverview(client.overview()).copy(endpoint = client.endpoint, loggedIn = true)
        }

    fun test(device: String, title: String, body: String, priority: Int) =
        authenticated(R.string.admin_test_accepted) { client ->
            client.test(device, title, body, priority)
            mutable.value
        }

    fun dismissSecret() {
        mutable.value = mutable.value.copy(secret = null)
    }

    /** Drops a finished dialog's outcome so the next one opens clean. */
    fun dismissAction() {
        if (mutable.value.awaiting == null && mutable.value.actionFailure != null)
            mutable.value = mutable.value.copy(actionFailure = null)
    }

    private fun authenticated(
        success: Int?,
        action: AdminAction? = null,
        work: (ServerAdministration) -> AdminUiState,
    ) {
        val client = session ?: return
        run(success, action) { work(client) }
    }

    private fun run(
        success: Int?,
        action: AdminAction? = null,
        onSuccess: suspend () -> Unit = {},
        work: () -> AdminUiState,
    ) {
        if (mutable.value.busy) return
        val captured = generation
        mutable.value =
            mutable.value.copy(
                busy = true,
                feedback = null,
                refreshFailure = null,
                awaiting = action,
                actionFailure = null,
            )
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { work() }
                if (captured == generation) {
                    mutable.value = result.copy(busy = true, feedback = success)
                    onSuccess()
                    if (captured == generation) mutable.value = mutable.value.copy(busy = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (captured != generation) return@launch
                if (failure is AdminRequestException && failure.status in listOf(401, 403)) {
                    session = null
                    val cleared = persistCredentials { credentialStore?.clear() }
                    if (captured != generation) return@launch
                    mutable.value = AdminUiState(endpoint = mutable.value.endpoint)
                    if (!cleared) {
                        mutable.value = mutable.value.copy(restoreFailure = R.string.admin_credentials_clear_failed)
                        return@launch
                    }
                }
                val feedback =
                    when (failure) {
                        is AdminRequestException ->
                            when (failure.status) {
                                401,
                                403 -> R.string.admin_unauthorized
                                404 -> R.string.admin_unavailable
                                429 -> R.string.admin_rate_limited
                                507 -> R.string.admin_capacity
                                in 300..399 -> R.string.admin_redirect
                                else -> R.string.admin_request_failed
                            }
                        is io.github.beacon.app.data.remote.AdminInputException -> R.string.admin_input_limits
                        is IllegalArgumentException -> R.string.admin_incompatible
                        else -> R.string.admin_network_failed
                    }
                if (!mutable.value.loggedIn || feedback == R.string.admin_unauthorized) {
                    session = null
                    mutable.value = AdminUiState(endpoint = mutable.value.endpoint, feedback = feedback)
                } else
                    mutable.value =
                        if (success == null)
                            mutable.value.copy(
                                busy = false,
                                feedback = null,
                                refreshFailure = feedback,
                                awaiting = null,
                            )
                        else
                            mutable.value.copy(
                                busy = false,
                                feedback = feedback,
                                awaiting = null,
                                actionFailure = feedback,
                            )
            }
        }
    }

    companion object {
        internal fun parseOverview(json: JSONObject): AdminUiState {
            // Older servers omitted these fields but expose the same administrative contract.
            require(ProtocolContract.adminSupported(json.optInt("admin_protocol", 1)))
            val devices = json.optJSONArray("devices")
            val applications = json.optJSONArray("applications")
            val storage = json.getJSONObject("storage")
            return AdminUiState(
                overviewLoaded = true,
                version = json.optString("version", "—"),
                protocol = json.optInt("protocol", ProtocolContract.MIN_HTTP),
                serverId = json.getString("server_id"),
                messages = storage.getLong("messages"),
                pending = storage.getLong("pending"),
                databaseBytes = storage.getLong("database_allocated_bytes"),
                devices =
                    List(devices?.length() ?: 0) { index ->
                        devices!!.getJSONObject(index).let {
                            AdminDevice(
                                it.getString("device_id"),
                                it.getString("name"),
                                it.getBoolean("revoked"),
                            )
                        }
                    },
                applications =
                    List(applications?.length() ?: 0) { index ->
                        applications!!.getJSONObject(index).let {
                            AdminApplication(
                                it.getString("id"),
                                it.getString("device_id"),
                                it.getString("name"),
                                it.getInt("default_priority"),
                                it.getBoolean("revoked"),
                            )
                        }
                    },
            )
        }
    }
}
