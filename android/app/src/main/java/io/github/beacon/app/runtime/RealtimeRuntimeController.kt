package io.github.beacon.app.runtime

import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import io.github.beacon.app.data.local.ReceivePlanStore
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.data.local.TransportSecretStore
import io.github.beacon.app.data.remote.HttpMessageSync
import io.github.beacon.app.data.remote.RealtimeClient
import io.github.beacon.app.data.remote.RealtimeMessageDecoder
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.policy.SyncFailure
import io.github.beacon.app.domain.receive.*
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Runtime actor owns settings, credentials, SQLite and every cross-transport transition. */
internal class RealtimeRuntimeController(
    private val service: RuntimeProbeService,
    private val worker: ExecutorService,
    private val store: RuntimeMessageStore,
    private val polling: () -> PollingState,
    private val savePolling: (PollingState) -> Unit,
    private val catchUp: () -> Unit,
) {
    private val preferences = ReceivePlanStore(service)
    var plan: ReceivePlan = preferences.load()
        private set

    @Volatile
    var status = "即时接收未启动"
        private set

    @Volatile
    var running = false
        private set

    var deliveryRevision = 0L
        private set

    private val epoch = AtomicLong()
    private var session: RealtimeClient? = null
    private var nextConfigRetry = 0L
    private var failures = 0
    @Volatile private var foreground = false
    @Volatile private var destroyed = false
    private val main = Handler(Looper.getMainLooper())
    private var receiptTask: Runnable? = null
    private var notificationTask: Runnable? = null
    private var receivedInBatch = 0
    private var insertedInBatch = 0
    private var lastReceivedMs = 0L
    private var receiptNamespace: String? = null
    private var receiptRetryAtMs = 0L
    private val heartbeatMemory = io.github.beacon.app.data.local.HeartbeatMemoryStore(service)

    fun coalesceNetworkWork() = session?.coalesceNetworkWork()

    fun messagesChanged() {
        deliveryRevision++
        RuntimeUiEvents.changed(service)
    }

    // Evaluated on the runtime actor. A failed receipt must still get periodic HTTP recovery.
    val canSkipPeriodicSync: Boolean
        get() = running && receiptNamespace?.let { store.pendingIds(it).isEmpty() } == true

    private fun queueReceipts(
        settings: TransportSettings,
        namespace: String,
        valid: () -> Boolean,
    ) {
        if (
            receiptTask != null ||
                service.automaticHttpWaiting() ||
                android.os.SystemClock.elapsedRealtime() < receiptRetryAtMs
        )
            return
        val task =
            object : Runnable {
                override fun run() {
                    runCatching {
                        worker.execute {
                            if (receiptTask !== this) return@execute
                            receiptTask = null
                            if (!valid()) return@execute
                            if (service.automaticHttpWaiting()) return@execute
                            try {
                                coalesceNetworkWork()
                                HttpMessageSync(settings, store, valid)
                                    .acknowledgePending(namespace)
                                if (valid() && store.pendingIds(namespace).isNotEmpty())
                                    queueReceipts(settings, namespace, valid)
                            } catch (_: SyncAuthenticationException) {
                                savePolling(
                                    polling()
                                        .copy(
                                            blocked = "接收凭证失效，请重新绑定服务器",
                                            lastOutcome = SyncOutcome.AUTH,
                                        )
                                )
                                reconcile()
                                return@execute
                            } catch (error: Exception) {
                                RetryAfterPolicy.from(error)?.let(service::deferAutomaticHttp)
                                receiptRetryAtMs =
                                    android.os.SystemClock.elapsedRealtime() +
                                        maxOf(30_000L, RetryAfterPolicy.from(error) ?: 0L)
                                // Durable receipts survive connection changes and are retried by
                                // HTTP
                                // catch-up or the next received message; no idle retry timer is
                                // added.
                            }
                            if (valid() && receivedInBatch > 0) {
                                savePolling(
                                    polling()
                                        .copy(
                                            lastSuccessMs = lastReceivedMs,
                                            lastSuccessInserted = insertedInBatch,
                                            lastOutcome = SyncOutcome.OK,
                                        )
                                )
                                receivedInBatch = 0
                                insertedInBatch = 0
                            }
                        }
                    }
                }
            }
        receiptTask = task
        main.postDelayed(task, ReceiveNetworkWorkPolicy.RECEIPT_BATCH_DELAY_MS)
    }

    /**
     * One notification pass per burst. Every received message used to flush the whole pending set
     * immediately: the settings reads, the channel lookup and the shade update ran once per message
     * even though a catch-up batch saves them all within the same window. Notifications keep a
     * shorter delay than receipt batching; the intents are durable, so a skipped pass is not lost —
     * the next message or HTTP sync posts them. `reset()` deliberately does not cancel this: a
     * network change must not swallow an alert for a message that is already saved.
     */
    private fun queueNotifications() {
        if (notificationTask != null) return
        val task =
            object : Runnable {
                override fun run() {
                    runCatching {
                        worker.execute {
                            if (notificationTask !== this) return@execute
                            notificationTask = null
                            if (destroyed) return@execute
                            runCatching { flushNotifications(service, store) }
                            runReceiveCleanup(service, store)
                        }
                    }
                }
            }
        notificationTask = task
        main.postDelayed(task, 250L)
    }

    private var lastNetwork: Network? = null
    private var offline = false
    private var networkRegistered = false
    private var conditionsRegistered = false
    private var conditionsBattery = false
    private var powerPaused = false
    private var powerSaving = false
    private val conditionsReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                networkChanged()
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    runCatching { worker.execute { if (!destroyed) coalesceNetworkWork() } }
                }
            }
        }
    private var configFailure = RealtimeFailure.NONE
    private var lastRecordedTransition: Pair<RuntimeEventKind, RealtimeFailure>? = null

    private fun recordTransition(
        kind: RuntimeEventKind,
        failure: RealtimeFailure = RealtimeFailure.NONE,
    ) {
        val transition = kind to failure
        if (lastRecordedTransition != transition) {
            lastRecordedTransition = transition
            service.recordEvent(kind, failure = failure)
        }
    }

    val diagnostics: RealtimeDiagnostics
        get() = session?.diagnostics ?: RealtimeDiagnostics(failure = configFailure)

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = debounceNetworkChanged()

            override fun onLost(network: Network) = debounceNetworkChanged()

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = debounceNetworkChanged()
        }

    private val networkMonitor = Runnable { networkChanged() }

    private fun debounceNetworkChanged() {
        if (destroyed) return
        main.removeCallbacks(networkMonitor)
        main.postDelayed(networkMonitor, 500L)
    }

    fun pollingChanged() = networkChanged()

    fun verifiedManualSync() {
        if (destroyed || session?.requiresRestart != true) return
        reset()
        reconcile()
    }

    private fun networkChanged() {
        if (destroyed || !reconcileQueued.compareAndSet(false, true)) return
        runCatching {
            worker.execute {
                reconcileQueued.set(false)
                if (!destroyed) reconcile()
            }
        }
            .onFailure { reconcileQueued.set(false) }
    }

    private val reconcileQueued = java.util.concurrent.atomic.AtomicBoolean()
    private val monitor = Runnable { networkChanged() }

    private fun updateConditions() {
        val required = foreground
        val batteryRequired =
            plan.mode == ReceiveMode.RULES && (plan.lowBatteryEnabled || plan.realtimeWhileCharging)
        if (required == conditionsRegistered && (!required || batteryRequired == conditionsBattery))
            return
        if (conditionsRegistered) {
            runCatching { service.unregisterReceiver(conditionsReceiver) }
            conditionsRegistered = false
        }
        if (!required) return
        conditionsBattery = batteryRequired
        conditionsRegistered =
            runCatching {
                    androidx.core.content.ContextCompat.registerReceiver(
                        service,
                        conditionsReceiver,
                        IntentFilter().apply {
                            addAction(android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                            addAction(Intent.ACTION_SCREEN_ON)
                            addAction(Intent.ACTION_SCREEN_OFF)
                            if (batteryRequired) addAction(Intent.ACTION_BATTERY_CHANGED)
                            addAction(Intent.ACTION_TIME_CHANGED)
                            addAction(Intent.ACTION_TIMEZONE_CHANGED)
                        },
                        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    true
                }
                .getOrDefault(false)
    }

    fun started() {
        if (destroyed) return
        if (!networkRegistered) {
            networkRegistered =
                runCatching {
                        service
                            .getSystemService(ConnectivityManager::class.java)
                            .registerDefaultNetworkCallback(networkCallback)
                        true
                    }
                    .getOrDefault(false)
        }
        if (foreground) {
            reconcile()
            publishStatus()
            return
        }
        foreground = true
        publishedStatus = null
        reconcile()
    }

    fun save(plan: ReceivePlan) {
        preferences.save(plan)
        this.plan = plan

        service.recordEvent(RuntimeEventKind.MODE_SAVED)
        reset()
        reconcile()
    }

    fun reloadPreferences() {
        plan = preferences.load()
        reset()
        reconcile()
    }

    fun reset() {
        receiptTask?.let { main.removeCallbacks(it) }
        receiptTask = null
        receivedInBatch = 0
        insertedInBatch = 0
        receiptNamespace = null
        receiptRetryAtMs = 0L
        epoch.incrementAndGet()
        if (session != null) recordTransition(RuntimeEventKind.REALTIME_STOPPED)
        session?.close()
        session = null
        running = false
        nextConfigRetry = 0
        failures = 0
        configFailure = RealtimeFailure.NONE
    }

    fun reconcile() {
        try {
            updateConditions()
            reconcileNow()
        } finally {
            updateConditions()
            main.removeCallbacks(monitor)
            if (foreground && !destroyed) {
                // While the device is offline every deadline is unreachable work: a configuration
                // retry cannot succeed and a rule boundary only re-reads the same missing network.
                // The registered default-network callback is the wake source that ends the wait, so
                // no timer is posted. If that callback could not be registered, nothing would ever
                // wake this actor again, so a single fallback keeps reception from being stranded.
                val delay =
                    if (powerPaused)
                        if (conditionsRegistered) null
                        else ReceiveDeadlinePolicy.OFFLINE_FALLBACK_MS
                    else if (offline && networkRegistered) null
                    else if (offline) ReceiveDeadlinePolicy.OFFLINE_FALLBACK_MS
                    else
                        ReceiveDeadlinePolicy.delayMs(
                            plan,
                            java.time.ZonedDateTime.now(),
                            nextConfigRetry,
                        )
                delay?.let { main.postDelayed(monitor, it) }
            }
        }
    }

    private fun reconcileNow() {
        val state = polling()
        val suspended = runCatching {
            io.github.beacon.app.data.local.RecoveryStatusStore(service).load().status ==
                RecoveryStatus.CRASH_SUSPENDED
        }
            .getOrDefault(true)
        if (suspended) {
            reset()
            status = "意外退出过多，请手动开启接收"
            if (foreground) {
                foreground = false
                main.removeCallbacks(monitor)
                main.post { service.endRealtimeForeground() }
            }
            return
        }
        if (destroyed || !state.shouldSchedule || plan.mode == ReceiveMode.POWER_SAVING) {
            reset()
            status =
                when {
                    state.blocked.isNotEmpty() -> "自动接收已阻止：${state.blocked}"
                    !state.config.enabled -> "自动接收已暂停"
                    else -> "使用省电接收"
                }
            if (foreground) {
                foreground = false
                main.removeCallbacks(monitor)
                main.post { service.endRealtimeForeground() }
            }
            return
        }
        if (!foreground) {
            status = "等待应用启动即时服务"
            return
        }
        val power = service.getSystemService(android.os.PowerManager::class.java)
        val saving = power.isPowerSaveMode && !power.isInteractive
        if (saving != powerSaving) {
            powerSaving = saving
            reset()
        }
        powerPaused =
            power.isDeviceIdleMode && !power.isIgnoringBatteryOptimizations(service.packageName)
        if (powerPaused) {
            if (session != null) reset()
            status = "系统休眠 · 等待唤醒或维护窗口补收"
            publishStatus()
            return
        }
        val manager = service.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        if (network != lastNetwork) {
            lastNetwork = network
            recordTransition(RuntimeEventKind.NETWORK_CHANGED)
            reset()
        }
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        val online =
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        offline = !online
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val battery =
            if (
                plan.mode == ReceiveMode.RULES &&
                    (plan.lowBatteryEnabled || plan.realtimeWhileCharging)
            )
                service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            else null
        val percent =
            battery?.let {
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
                if (scale > 0)
                    (it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale).coerceIn(0, 100)
                else 0
            } ?: 0
        val localTime = java.time.LocalTime.now()
        val minute = localTime.hour * 60 + localTime.minute
        val charging =
            battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } == true
        if (
            !ReceiveModePolicy.realtime(
                plan,
                state.shouldSchedule,
                online,
                charging,
                wifi,
                percent,
                minute,
            )
        ) {
            if (session != null) reset()
            status = if (!online) "等待网络恢复 · HTTP 补收保留" else "规则未满足 · 使用省电接收"
            recordTransition(
                if (!online) RuntimeEventKind.WAIT_NETWORK else RuntimeEventKind.WAIT_RULES
            )
            publishStatus()
            return
        }
        if (session != null || System.currentTimeMillis() < nextConfigRetry) return
        val capture = epoch.incrementAndGet()
        val control = service.controlGeneration()
        fun valid() = epoch.get() == capture && service.controlGeneration() == control && !destroyed
        status = "正在获取即时连接配置"
        publishStatus()
        try {
            val settings = TransportSecretStore(service).load()
            val config = HttpMessageSync(settings, store, ::valid).realtimeConfiguration()
            val properties = manager.getLinkProperties(network)
            // Permission-free network fingerprint; always verify a hint before increasing probes.
            val fingerprint =
                listOf(
                        settings.endpoint,
                        if (wifi) "wifi" else "mobile",
                        properties?.interfaceName,
                        properties?.dnsServers?.mapNotNull { it.hostAddress }?.sorted(),
                        properties?.routes?.mapNotNull { it.gateway?.hostAddress }?.sorted(),
                    )
                    .joinToString("|")
            val memoryKey =
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(fingerprint.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
            val heartbeatPolicy =
                if (plan.adaptiveHeartbeat && config.adaptiveHeartbeatSupported)
                    AdaptiveHeartbeatPolicy(heartbeatMemory.load(memoryKey))
                else null
            receiptNamespace =
                io.github.beacon.app.data.remote.MessageIdentity.namespace(
                    config.serverId,
                    config.deviceId,
                )
            val decoder =
                RealtimeMessageDecoder(config.serverId, config.deviceId, config.clockOffsetMs)
            val next =
                RealtimeClient(
                    config,
                    settings.token,
                    heartbeatSeconds = plan.heartbeatSeconds,
                    heartbeatPolicy = heartbeatPolicy,
                    powerSaving = powerSaving,
                    batchOrdinaryMessages = plan.batchOrdinaryMessages,
                    onVerifiedHeartbeat = { seconds ->
                        runCatching {
                            worker.execute { if (valid()) heartbeatMemory.save(memoryKey, seconds) }
                        }
                    },
                    onHeartbeat = {
                        runCatching {
                            worker.execute {
                                if (!valid()) return@execute
                                receiptTask?.let { task ->
                                    main.removeCallbacks(task)
                                    task.run()
                                }
                            }
                        }
                    },
                    receive = { payload ->
                        if (!valid()) throw IOException("Session changed")
                        worker
                            .submit {
                                if (!valid() || !polling().shouldSchedule)
                                    throw IOException("Session changed")
                                val message = decoder.decode(payload, System.currentTimeMillis())
                                // Persist first. Server confirmation and system notifications are
                                // independent.
                                ReceiveMessageValidation.validate(message)
                                val saved = store.save(message)
                                receivedInBatch++
                                if (saved == SaveResult.INSERTED) insertedInBatch++
                                lastReceivedMs = System.currentTimeMillis()
                                queueReceipts(settings, message.server, ::valid)
                                messagesChanged()
                                queueNotifications()
                            }
                            .get(45, TimeUnit.SECONDS)
                    },
                    onState = { value ->
                        runCatching {
                            worker.execute {
                                if (epoch.get() != capture) return@execute
                                running = value == RealtimeClient.State.CONNECTED
                                status =
                                    when (value) {
                                        RealtimeClient.State.CONNECTING -> "即时接收连接中"
                                        RealtimeClient.State.CONNECTED -> "即时接收已连接，断线后自动补收"
                                        RealtimeClient.State.RETRYING -> "连接断开，正在重试，自动补收保留"
                                        RealtimeClient.State.AUTH_FAILED -> "接收凭证失效，请重新绑定服务器"
                                        RealtimeClient.State.TLS_FAILED -> "TLS 校验失败，请检查服务器证书"
                                        RealtimeClient.State.CONFIG_FAILED -> "即时配置或订阅不受支持，请检查服务器"
                                        RealtimeClient.State.STOPPED -> "即时接收已停止"
                                    }
                                if (value == RealtimeClient.State.AUTH_FAILED) {
                                    savePolling(
                                        polling()
                                            .copy(blocked = status, lastOutcome = SyncOutcome.AUTH)
                                    )
                                }
                                val kind =
                                    when (value) {
                                        RealtimeClient.State.CONNECTING ->
                                            RuntimeEventKind.REALTIME_CONNECTING
                                        RealtimeClient.State.CONNECTED ->
                                            RuntimeEventKind.REALTIME_CONNECTED
                                        RealtimeClient.State.RETRYING ->
                                            RuntimeEventKind.REALTIME_RETRYING
                                        RealtimeClient.State.STOPPED ->
                                            RuntimeEventKind.REALTIME_STOPPED
                                        else -> RuntimeEventKind.REALTIME_REJECTED
                                    }
                                recordTransition(
                                    kind,
                                    if (value == RealtimeClient.State.CONNECTED)
                                        RealtimeFailure.NONE
                                    else diagnostics.failure,
                                )
                                publishStatus()
                            }
                        }
                    },
                    onSubscribed = {
                        runCatching {
                            worker.execute {
                                if (epoch.get() == capture && polling().shouldSchedule) catchUp()
                            }
                        }
                    },
                )
            session = next
            next.start()
        } catch (error: Exception) {
            configFailure =
                io.github.beacon.app.data.remote.RealtimeFailureClassifier.classify(error)
            val outcome = SyncFailure.outcome(error)
            recordTransition(RuntimeEventKind.REALTIME_CONFIG_FAILED, configFailure)
            if (
                outcome == SyncOutcome.AUTH ||
                    outcome == SyncOutcome.CONFIG ||
                    outcome == SyncOutcome.TLS
            ) {
                savePolling(
                    polling()
                        .copy(blocked = SyncFailure.describe(error, outcome), lastOutcome = outcome)
                )
                status = "即时配置不可用：${SyncFailure.describe(error, outcome)}"
            } else {
                failures = (failures + 1).coerceAtMost(100)
                nextConfigRetry =
                    System.currentTimeMillis() +
                        maxOf(
                            RealtimeReconnectPolicy.jitteredDelayMs(failures),
                            RetryAfterPolicy.from(error) ?: 0L,
                        )
                status = "即时配置暂不可用 · 自动重试，HTTP 补收保留"
            }
            publishStatus()
        }
    }

    private var publishedStatus: String? = null

    private fun publishStatus() {
        RuntimeUiEvents.changed(service)
        val text = status
        main.post {
            if (foreground && !destroyed && publishedStatus != text) {
                service.showRealtimeStatus(text)
                publishedStatus = text
            }
        }
    }

    fun destroy() {
        cancel()
        reset()
        if (conditionsRegistered) {
            runCatching { service.unregisterReceiver(conditionsReceiver) }
            conditionsRegistered = false
        }
        if (networkRegistered) {
            runCatching {
                service
                    .getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(networkCallback)
            }
            networkRegistered = false
        }
    }

    fun cancel() {
        destroyed = true
        epoch.incrementAndGet()
        main.removeCallbacks(monitor)
        main.removeCallbacks(networkMonitor)
        receiptTask?.let { main.removeCallbacks(it) }
        notificationTask?.let { main.removeCallbacks(it) }
        // Dropped explicitly: a leftover handle would keep queueNotifications from ever scheduling
        // again on a restarted session.
        receiptTask = null
        notificationTask = null
    }
}
