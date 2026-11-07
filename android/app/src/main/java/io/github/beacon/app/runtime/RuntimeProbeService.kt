package io.github.beacon.app.runtime

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import io.github.beacon.app.core.serialExecutor
import io.github.beacon.app.data.local.CrashRecoveryStore
import io.github.beacon.app.data.local.PollingStateStore
import io.github.beacon.app.data.local.ReceivePlanStore
import io.github.beacon.app.data.local.RecoveryStatusStore
import io.github.beacon.app.data.local.RuntimeEventStore
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.data.local.TransportSecretStore
import io.github.beacon.app.data.remote.HttpMessageSync
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.PollingState
import io.github.beacon.app.domain.model.RealtimeFailure
import io.github.beacon.app.domain.model.RecoveryStatus
import io.github.beacon.app.domain.model.RuntimeEvent
import io.github.beacon.app.domain.model.RuntimeEventKind
import io.github.beacon.app.domain.model.SyncOutcome
import io.github.beacon.app.domain.policy.CrashRecoveryAction
import io.github.beacon.app.domain.policy.CrashRecoveryPolicy
import io.github.beacon.app.domain.policy.ReceiveRecoveryPolicy
import io.github.beacon.app.domain.policy.SyncFailure
import io.github.beacon.app.domain.receive.PollingPolicy
import io.github.beacon.app.domain.receive.SyncReport
import io.github.beacon.app.ipc.RuntimeProtocol.AUTO_SYNC
import io.github.beacon.app.ipc.RuntimeProtocol.CONFIGURE
import io.github.beacon.app.ipc.RuntimeProtocol.DELETE
import io.github.beacon.app.ipc.RuntimeProtocol.DESCRIPTOR
import io.github.beacon.app.ipc.RuntimeProtocol.DETAIL
import io.github.beacon.app.ipc.RuntimeProtocol.GET_CONNECTION
import io.github.beacon.app.ipc.RuntimeProtocol.GET_CONTROL
import io.github.beacon.app.ipc.RuntimeProtocol.INJECT_TEST
import io.github.beacon.app.ipc.RuntimeProtocol.MUTATE
import io.github.beacon.app.ipc.RuntimeProtocol.NOTIFY
import io.github.beacon.app.ipc.RuntimeProtocol.PING
import io.github.beacon.app.ipc.RuntimeProtocol.QUERY
import io.github.beacon.app.ipc.RuntimeProtocol.RECENT
import io.github.beacon.app.ipc.RuntimeProtocol.SET_POLLING
import io.github.beacon.app.ipc.RuntimeProtocol.STOP_RUN
import io.github.beacon.app.ipc.RuntimeProtocol.SYNC
import io.github.beacon.app.ipc.RuntimeProtocol.UNDO_DELETE

/** Own-UID runtime: serialized storage, bounded HTTP sync and user-enabled foreground reception. */
class RuntimeProbeService : Service() {
    companion object {
        const val START_RECEIVE = "io.github.beacon.START_RECEIVE"

        /** Set only by [CrashRecoveryAlarm]; it marks a retry so the budget is not reset. */
        const val RECOVERY_RETRY = "io.github.beacon.RECOVERY_RETRY"
        private val FOREGROUND_CHANNEL =
            io.github.beacon.app.domain.model.NotificationChannelKind.RUNTIME.id
        private const val FOREGROUND_ID = 901
    }

    internal val worker = serialExecutor("beacon-runtime")
    internal val scheduler = serialExecutor("beacon-scheduler")
    internal val serverProbe = serialExecutor("beacon-server-probe")

    internal val statusStore by lazy { ServerStatusStore(this) }
    private val storeDelegate = lazy { RuntimeMessageStore(this) }
    internal val store by storeDelegate
    internal val events by lazy { RuntimeEventStore(this) }

    internal fun recordEvent(
        kind: RuntimeEventKind,
        outcome: SyncOutcome? = null,
        failure: RealtimeFailure = RealtimeFailure.NONE,
    ) {
        // Journal failure must never alter persistence, confirmation or reception.
        runCatching {
            events.append(RuntimeEvent(System.currentTimeMillis(), kind, outcome, failure))
        }
    }

    internal val generation = java.util.concurrent.atomic.AtomicLong()
    internal val syncing = java.util.concurrent.atomic.AtomicBoolean()
    private val networkWork = io.github.beacon.app.domain.receive.ReceiveNetworkWorkPolicy()
    internal val activeRuns =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()
    internal val controlLoaded = java.util.concurrent.CountDownLatch(1)
    @Volatile internal var pollingState = PollingState()
    private val realtimeDelegate = lazy {
        RealtimeRuntimeController(this, worker, store, { pollingState }, ::saveState) {
            sync(true, generation.get(), null, catchUp = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var reportOutcome = false
        var attempts = 0
        when (intent?.action) {
            START_RECEIVE -> {
                // An explicit start from the client or a boot restore is a fresh budget: only the
                // user's own action may lift a suspension.
                CrashRecoveryAlarm.cancel(this)
                runCatching { CrashRecoveryStore(this).save(CrashRecoveryPolicy.reset()) }
                clearRecoveryRecord()
            }
            else -> {
                CrashRecoveryAlarm.cancel(this)
                // A sticky redelivery carries no intent: the system restarted this process after it
                // died. Alarm retries use the same admission: WAIT has not counted an attempt.
                val store = CrashRecoveryStore(this)
                val admission =
                    CrashRecoveryPolicy.admit(
                        store.load(),
                        android.os.SystemClock.elapsedRealtime(),
                    )
                if (runCatching { store.save(admission.next) }.isFailure) {
                    // An unpersisted budget must never permit unlimited automatic restarts.
                    recordRecovery(RecoveryStatus.SETTINGS_ERROR, admission.next.attempts)
                    showRealtimeStatus("恢复记录保存失败，请检查可用空间后手动开启")
                    endRealtimeForeground()
                    return START_NOT_STICKY
                }
                attempts = admission.next.attempts
                when (admission.action) {
                    CrashRecoveryAction.START -> {
                        recordEvent(RuntimeEventKind.UNEXPECTED_EXIT_RECOVERY)
                        reportOutcome = true
                    }
                    CrashRecoveryAction.WAIT -> {
                        CrashRecoveryAlarm.schedule(this, admission.delayMs)
                        recordRecovery(RecoveryStatus.CRASH_RETRY_WAITING, attempts)
                        showRealtimeStatus("意外退出恢复中，${admission.delayMs / 1000} 秒后自动重试")
                        // Reception deliberately stays down until the alarm asks again, and the
                        // alarm is already the durable retry, so a sticky redelivery would only add
                        // a second, unbounded one: the system would restart the process on its own
                        // schedule instead of the budget's. Staying not sticky keeps the retry
                        // cadence exactly the policy's 5/15/60 s, and it does not stop the service
                        // now — the wait and its notification remain until the alarm fires.
                        return START_NOT_STICKY
                    }
                    CrashRecoveryAction.SUSPEND -> {
                        recordEvent(RuntimeEventKind.RECOVERY_SUSPENDED)
                        recordRecovery(RecoveryStatus.CRASH_SUSPENDED, attempts)
                        showRealtimeStatus("意外退出过多，已停止自动恢复，请在应用内手动开启")
                        endRealtimeForeground()
                        return START_NOT_STICKY
                    }
                }
            }
        }
        showRealtimeStatus("正在恢复自动接收")
        worker.execute {
            if (reportOutcome) {
                // Neither an automatic retry nor a boot restore may override the persisted user
                // switch: the same gate that guards boot recovery is consulted here, and a block
                // wins over the recovery attempt.
                val blocked = runCatching {
                    ReceiveRecoveryPolicy.block(ReceivePlanStore(this).load(), pollingState)
                }
                    .getOrNull()
                recordRecovery(blocked ?: RecoveryStatus.START_REQUESTED, attempts)
            }
            realtime.started()
        }
        return START_STICKY
    }

    /**
     * A user-visible start clears a recovery verdict, because the user just acted. Any other status
     * describes boot or configuration rather than recovery and is left alone — the persisted
     * receiving switch may not have reached this process yet.
     */
    internal fun clearRecoveryRecord() {
        runCatching {
            val history = RecoveryStatusStore(this)
            val current = history.load()
            if (
                current.status == RecoveryStatus.CRASH_SUSPENDED ||
                    current.status == RecoveryStatus.CRASH_RETRY_WAITING
            )
                history.record(RecoveryStatus.START_REQUESTED, 0)
        }
    }

    /** Journals a recovery outcome; presentation must never take down reception. */
    internal fun recordRecovery(status: RecoveryStatus, attempts: Int) {
        runCatching {
            RecoveryStatusStore(this).record(status, attempts)
            RuntimeUiEvents.changed(this)
        }
    }

    internal val realtime by realtimeDelegate

    /**
     * The foreground channel is created once per process. [showRealtimeStatus] runs on every
     * connection-state change, and `createNotificationChannel` is a binder round trip into the
     * system server that returns identical results after the first call, so repeating it turned a
     * status line update into avoidable IPC.
     */
    @Volatile private var foregroundChannelReady = false

    internal fun controlGeneration(): Long = generation.get()

    internal fun showRealtimeStatus(text: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        if (!foregroundChannelReady) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    FOREGROUND_CHANNEL,
                    getString(io.github.beacon.app.R.string.channel_runtime),
                    android.app.NotificationManager.IMPORTANCE_LOW,
                )
            )
            foregroundChannelReady = true
        }
        val open =
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, io.github.beacon.app.MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notice =
            android.app.Notification.Builder(this, FOREGROUND_CHANNEL)
                .setSmallIcon(io.github.beacon.app.R.drawable.ic_notification)
                .setContentTitle(getString(io.github.beacon.app.R.string.runtime_notification_title))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        if (android.os.Build.VERSION.SDK_INT >= 34)
            startForeground(
                FOREGROUND_ID,
                notice,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        else startForeground(FOREGROUND_ID, notice)
    }

    internal fun endRealtimeForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        worker.execute {
            try {
                pollingState =
                    try {
                        PollingStateStore(this).load()
                    } catch (_: Exception) {
                        PollingState(blocked = "接收设置读取失败，请重新保存设置")
                    }
                reconcileSchedule()
                // A locked boot parked its wait in device-protected storage; this is the first
                // start that can read it back, so the real outcome is journalled exactly once.
                val parked = RecoveryStatusStore(this).consumeAwaitingUnlock()
                recordEvent(RuntimeEventKind.SERVICE_STARTED)
                if (parked) recordEvent(RuntimeEventKind.RECOVERY_AWAITING_UNLOCK)
            } finally {
                controlLoaded.countDown()
            }
        }
    }

    internal fun reconcileSchedule() {
        // Independent from the receive queue: cancellation may call back into the service.
        scheduler.execute {
            runCatching { PollingScheduler.apply(this, pollingState) }
                .onFailure {
                    android.util.Log.w("Beacon", "Polling schedule reconciliation failed", it)
                }
        }
    }

    internal fun saveState(value: PollingState) {
        // Several call sites pass the current state when only a side effect (a reset, a recheck)
        // is wanted. Committing an unchanged value costs a synchronous preference write, a UI
        // broadcast and a WorkManager reconcile, so skip all three when nothing differs.
        if (value == pollingState) return
        val revised = value.copy(revision = pollingState.revision + 1)
        PollingStateStore(this).save(revised)
        val enabledChanged = pollingState.config.enabled != revised.config.enabled
        val schedulingChanged = pollingState.shouldSchedule != revised.shouldSchedule
        pollingState = revised
        RuntimeUiEvents.changed(this)
        reconcileSchedule()

        if (enabledChanged)
            recordEvent(
                if (revised.config.enabled) RuntimeEventKind.RECEIVE_ENABLED
                else RuntimeEventKind.RECEIVE_PAUSED
            )
        if (schedulingChanged && realtimeDelegate.isInitialized()) realtime.pollingChanged()
    }

    internal fun sync(
        automatic: Boolean,
        expectedGeneration: Long,
        active: java.util.concurrent.atomic.AtomicBoolean?,
        catchUp: Boolean = false,
    ): SyncReport {
        fun allowed() =
            generation.get() == expectedGeneration &&
                (active?.get() != false) &&
                (!automatic || pollingState.shouldSchedule)
        val eventKind = if (automatic) RuntimeEventKind.AUTO_SYNC else RuntimeEventKind.MANUAL_SYNC
        if (!allowed()) {
            recordEvent(eventKind, SyncOutcome.STOPPED)
            return SyncReport(SyncOutcome.STOPPED, "自动接收已暂停或任务已停止")
        }
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        if (automatic && networkWork.waiting(expectedGeneration, nowElapsed)) {
            // Server cooldown must not defer a notification for an already saved message.
            if (allowed()) runCatching { flushNotifications(this, store) }
            return SyncReport(SyncOutcome.TRANSIENT, "服务器要求稍后重试；待接收消息和回执保留")
        }
        if (
            automatic &&
                !catchUp &&
                networkWork.skipPeriodicSync(
                    expectedGeneration,
                    nowElapsed,
                    realtime.canSkipPeriodicSync,
                )
        ) {
            // Skip network work only; retry durable local notification intents as before.
            if (allowed()) runCatching { flushNotifications(this, store) }
            return SyncReport(SyncOutcome.OK, "即时连接正常；本轮省略重复 HTTP 补收")
        }
        // The attempt timestamp is carried into the result record below instead of being committed
        // on its own first: nothing gates on it (it is diagnostics only), while a second commit
        // meant
        // a second synchronous preference fsync plus a second schedule reconciliation per run.
        val attemptedAtMs = System.currentTimeMillis()
        val settings =
            try {
                TransportSecretStore(this).load()
            } catch (_: Exception) {
                if (allowed())
                    saveState(
                        pollingState.copy(
                            blocked = PollingPolicy.blockingReason(SyncOutcome.CONFIG),
                            lastAttemptMs = attemptedAtMs,
                            lastOutcome = SyncOutcome.CONFIG,
                        )
                    )
                recordEvent(eventKind, SyncOutcome.CONFIG)
                return SyncReport(SyncOutcome.CONFIG, "连接配置不可用，请重新保存配置")
            }
        var outcome = SyncOutcome.OK
        var insertedCount: Int? = null
        val message =
            try {
                realtime.coalesceNetworkWork()
                val sync = HttpMessageSync(settings, store, ::allowed)
                sync.run().also { insertedCount = sync.insertedCount }
            } catch (e: Exception) {
                io.github.beacon.app.domain.receive.RetryAfterPolicy.from(e)?.let {
                    networkWork.defer(
                        expectedGeneration,
                        android.os.SystemClock.elapsedRealtime(),
                        it,
                    )
                }
                outcome = SyncFailure.outcome(e, e is android.database.sqlite.SQLiteException)
                SyncFailure.describe(e, outcome)
            }
        if (outcome == SyncOutcome.OK && allowed())
            networkWork.completed(expectedGeneration, android.os.SystemClock.elapsedRealtime())
        // A failed or cancelled sync may have committed earlier pages. Refresh those too.
        realtime.messagesChanged()
        if (allowed())
            saveState(
                pollingState.copy(
                    blocked = PollingPolicy.blockingReason(outcome),
                    lastAttemptMs = attemptedAtMs,
                    lastSuccessMs =
                        if (outcome == SyncOutcome.OK) System.currentTimeMillis()
                        else pollingState.lastSuccessMs,
                    lastSuccessInserted = if (outcome == SyncOutcome.OK) insertedCount
                        else pollingState.lastSuccessInserted,
                    lastOutcome = outcome,
                )
            )
        val notice =
            if (!allowed()) "提醒任务保留"
            else
                try {
                    flushNotifications(this, store)
                } catch (_: Exception) {
                    "通知提交失败；提醒任务保留"
                }
        if (outcome == SyncOutcome.OK && allowed()) {
            if (!automatic) realtime.verifiedManualSync()
            // Maintenance failure must never turn a completed receive/confirmation into a failure.
            runReceiveCleanup(this, store)
        }
        recordEvent(eventKind, outcome)
        return SyncReport(
            outcome,
            listOf(message, notice).filter { it.isNotBlank() }.joinToString("\n"),
        )
    }

    // Shared by periodic HTTP recovery and realtime receipt batches, on the runtime actor.
    internal fun deferAutomaticHttp(delayMs: Long) {
        networkWork.defer(generation.get(), android.os.SystemClock.elapsedRealtime(), delayMs)
    }

    internal fun automaticHttpWaiting(): Boolean =
        networkWork.waiting(generation.get(), android.os.SystemClock.elapsedRealtime())

    private val endpoint =
        object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (
                    code !in
                        listOf(
                            io.github.beacon.app.ipc.RuntimeProtocol.RECONCILE_POLLING,
                            io.github.beacon.app.ipc.RuntimeProtocol.SCHEDULE_TEST,
                            PING,
                            RECENT,
                            INJECT_TEST,
                            DELETE,
                            CONFIGURE,
                            SYNC,
                            NOTIFY,
                            GET_CONTROL,
                            SET_POLLING,
                            AUTO_SYNC,
                            STOP_RUN,
                            QUERY,
                            DETAIL,
                            MUTATE,
                            UNDO_DELETE,
                            GET_CONNECTION,
                            io.github.beacon.app.ipc.RuntimeProtocol.CHECK_SERVER,
                            io.github.beacon.app.ipc.RuntimeProtocol.BIND_DEVICE,
                            io.github.beacon.app.ipc.RuntimeProtocol.COPY_PUSH_KEY,
                            io.github.beacon.app.ipc.RuntimeProtocol.PREVIEW_CLEANUP,
                            io.github.beacon.app.ipc.RuntimeProtocol.CHECKPOINT_STORAGE,
                            io.github.beacon.app.ipc.RuntimeProtocol.MARK_ALL_READ,
                            io.github.beacon.app.ipc.RuntimeProtocol.APPLY_CLEANUP,
                            io.github.beacon.app.ipc.RuntimeProtocol.CLEANUP_SETTINGS,
                            io.github.beacon.app.ipc.RuntimeProtocol.RECEIVE_PLAN,
                            io.github.beacon.app.ipc.RuntimeProtocol.QUIET_HOURS,
                            io.github.beacon.app.ipc.RuntimeProtocol.NOTIFICATION_SETTINGS,
                            io.github.beacon.app.ipc.RuntimeProtocol.REMIND_MESSAGE,
                            io.github.beacon.app.ipc.RuntimeProtocol.CLEAR_CONNECTION,
                            io.github.beacon.app.ipc.RuntimeProtocol.GET_EVENTS,
                            io.github.beacon.app.ipc.RuntimeProtocol.CLEAR_EVENTS,
                            io.github.beacon.app.ipc.RuntimeProtocol.RECONNECT_REALTIME,
                            io.github.beacon.app.ipc.RuntimeProtocol.ROTATE_CREDENTIALS,
                            io.github.beacon.app.ipc.RuntimeProtocol.REVOKE_DEVICE,
                            io.github.beacon.app.ipc.RuntimeProtocol.EXPORT_SETTINGS,
                            io.github.beacon.app.ipc.RuntimeProtocol.IMPORT_SETTINGS,
                            io.github.beacon.app.ipc.RuntimeProtocol.CLEAR_ALL_MESSAGES,
                            io.github.beacon.app.ipc.RuntimeProtocol.BARK_ENCRYPTION,
                            io.github.beacon.app.ipc.RuntimeProtocol.BARK_ENCRYPTION_STATUS,
                            io.github.beacon.app.ipc.RuntimeProtocol.TEST_DELIVERY,
                            io.github.beacon.app.ipc.RuntimeProtocol.HISTORY_ARCHIVE,
                            io.github.beacon.app.ipc.RuntimeProtocol.PENDING_REMINDERS,
                            io.github.beacon.app.ipc.RuntimeProtocol.GET_STATUS,
                            io.github.beacon.app.ipc.RuntimeProtocol.SYNC_WIDGET_TOPICS,
                            io.github.beacon.app.ipc.RuntimeProtocol.TEMPORARY_MUTE,
                        )
                )
                    return super.onTransact(code, data, reply, flags)
                if (Binder.getCallingUid() != applicationInfo.uid)
                    throw SecurityException("Own UID required")
                data.enforceInterface(DESCRIPTOR)
                return handleSettingsCommands(code, data, reply) ||
                    handleConnectionCommands(code, data, reply) ||
                    handlePreferenceCommands(code, data, reply) ||
                    handleStorageCommands(code, data, reply) ||
                    handleCredentialCommands(code, data, reply) ||
                    handleQueryCommands(code, data, reply) ||
                    handleSyncCommands(code, data, reply) ||
                    handleLegacyCommands(code, data, reply)
            }
        }

    override fun onBind(intent: Intent): IBinder {
        RuntimeUiEvents.observe(this, true)
        return endpoint
    }

    override fun onUnbind(intent: Intent): Boolean {
        RuntimeUiEvents.observe(this, false)
        return true
    }

    override fun onRebind(intent: Intent) {
        super.onRebind(intent)
        RuntimeUiEvents.observe(this, true)
    }

    internal fun writeMessage(reply: Parcel, message: IncomingMessage) {
        reply.writeString(message.server)
        reply.writeString(message.id)
        reply.writeString(message.title)
        reply.writeString(message.body)
        reply.writeLong(message.createdAtMs)
        reply.writeLong(message.receivedAtMs)
        reply.writeInt(if (message.isRead) 1 else 0)
        reply.writeString(message.subtitle)
        reply.writeInt(message.priority)
        reply.writeInt(if (message.reminderPending) 1 else 0)
        reply.writeString(
            runCatching {
                    val raw = org.json.JSONObject(message.bark)
                    org.json
                        .JSONObject()
                        .apply {
                            for (name in listOf("image", "icon", "url", "sound")) if (raw.has(name))
                                put(name, raw.getString(name))
                        }
                        .toString()
                }
                .getOrDefault("")
        )
        reply.writeString(message.applicationId)
        reply.writeString(message.group)
        reply.writeLong(message.expiresAtMs)
        reply.writeString(message.applicationName)
        reply.writeString(
            io.github.beacon.app.data.remote.MessageActionsCodec.encode(message.actions)
        )
        reply.writeString(message.structuredBlocks)
    }

    override fun onDestroy() {
        RuntimeUiEvents.observe(this, false)
        serverProbe.shutdownNow()
        if (realtimeDelegate.isInitialized()) realtime.cancel()
        worker.execute {
            if (realtimeDelegate.isInitialized()) realtime.destroy()
            if (storeDelegate.isInitialized()) store.close()
            scheduler.shutdown()
        }
        worker.shutdown()
        super.onDestroy()
    }
}
