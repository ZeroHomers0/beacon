package io.github.beacon.app.runtime

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.core.currentProcessName
import io.github.beacon.app.core.serialExecutor
import io.github.beacon.app.data.local.CrashRecoveryStore
import io.github.beacon.app.data.local.NotificationSettingsStore
import io.github.beacon.app.data.local.PollingStateStore
import io.github.beacon.app.data.local.PollingWire
import io.github.beacon.app.data.local.QuietHoursStore
import io.github.beacon.app.data.local.ReceivePlanStore
import io.github.beacon.app.data.local.RecoveryStatusStore
import io.github.beacon.app.data.local.RuntimeEventStore
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.data.local.TransportSecretStore
import io.github.beacon.app.data.remote.BindingRejected
import io.github.beacon.app.data.remote.BindingResponseInvalid
import io.github.beacon.app.data.remote.HttpDeviceBinding
import io.github.beacon.app.data.remote.HttpMessageSync
import io.github.beacon.app.domain.model.BindingRequest
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageAction
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.MessageMutation
import io.github.beacon.app.domain.model.MessageMutationResult
import io.github.beacon.app.domain.model.MessagePage
import io.github.beacon.app.domain.model.MessageQuery
import io.github.beacon.app.domain.model.MessageReadFilter
import io.github.beacon.app.domain.model.NotificationSettings
import io.github.beacon.app.domain.model.PollingConfig
import io.github.beacon.app.domain.model.PollingState
import io.github.beacon.app.domain.model.RealtimeFailure
import io.github.beacon.app.domain.model.RecoveryStatus
import io.github.beacon.app.domain.model.RuntimeEvent
import io.github.beacon.app.domain.model.RuntimeEventKind
import io.github.beacon.app.domain.model.SyncOutcome
import io.github.beacon.app.domain.model.TransportSettings
import io.github.beacon.app.domain.policy.CrashRecoveryAction
import io.github.beacon.app.domain.policy.CrashRecoveryPolicy
import io.github.beacon.app.domain.policy.RealtimeRetryDecision
import io.github.beacon.app.domain.policy.RealtimeRetryPolicy
import io.github.beacon.app.domain.policy.ReceiveRecoveryPolicy
import io.github.beacon.app.domain.policy.SyncFailure
import io.github.beacon.app.domain.receive.PollingPolicy
import io.github.beacon.app.domain.receive.ReceivePipeline
import io.github.beacon.app.domain.receive.SyncReport
import io.github.beacon.app.ipc.NotificationSettingsWire
import io.github.beacon.app.ipc.QuietHoursWire
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
import io.github.beacon.app.runtime.notification.MessageNotifications

/** Own-UID runtime: serialized storage, bounded HTTP sync and user-enabled foreground reception. */
class RuntimeProbeService : Service() {
    companion object {
        const val START_RECEIVE = "io.github.beacon.START_RECEIVE"
        const val PAUSE_RECEIVE = "io.github.beacon.PAUSE_RECEIVE"

        /** Set only by [CrashRecoveryAlarm]; it marks a retry so the budget is not reset. */
        const val RECOVERY_RETRY = "io.github.beacon.RECOVERY_RETRY"
        private val FOREGROUND_CHANNEL =
            io.github.beacon.app.domain.model.NotificationChannelKind.RUNTIME.id
        private const val FOREGROUND_ID = 901
    }

    private val worker = serialExecutor("beacon-runtime")
    private val scheduler = serialExecutor("beacon-scheduler")
    private val serverProbe = serialExecutor("beacon-server-probe")
    private val storeDelegate = lazy { RuntimeMessageStore(this) }
    private val store by storeDelegate
    private val events by lazy { RuntimeEventStore(this) }

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

    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val syncing = java.util.concurrent.atomic.AtomicBoolean()
    private val activeRuns =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()
    private val controlLoaded = java.util.concurrent.CountDownLatch(1)
    @Volatile private var pollingState = PollingState()
    private val realtimeDelegate = lazy {
        RealtimeRuntimeController(this, worker, store, { pollingState }, ::saveState) {
            sync(true, generation.get(), null)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var reportOutcome = false
        var attempts = 0
        when (intent?.action) {
            PAUSE_RECEIVE -> {
                // A user stop is explicit: it clears the bounded budget and any pending retry, and
                // no recovery path may undo it.
                pauseRecoveryBudget()
                generation.incrementAndGet()
                worker.execute {
                    saveState(pollingState.copy(config = pollingState.config.copy(enabled = false)))
                    realtime.reconcile()
                }
                return START_NOT_STICKY
            }
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
    private fun clearRecoveryRecord() {
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
    private fun recordRecovery(status: RecoveryStatus, attempts: Int) {
        runCatching { RecoveryStatusStore(this).record(status, attempts) }
    }

    /** Only an explicit user stop or an explicit start may clear the bounded budget. */
    private fun pauseRecoveryBudget() {
        CrashRecoveryAlarm.cancel(this)
        runCatching { CrashRecoveryStore(this).save(CrashRecoveryPolicy.reset()) }
    }

    private val realtime by realtimeDelegate

    internal fun controlGeneration(): Long = generation.get()

    internal fun showRealtimeStatus(text: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                FOREGROUND_CHANNEL,
                getString(io.github.beacon.app.R.string.channel_runtime),
                android.app.NotificationManager.IMPORTANCE_LOW,
            )
        )
        val open =
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, io.github.beacon.app.MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val pause =
            android.app.PendingIntent.getService(
                this,
                1,
                Intent(this, RuntimeProbeService::class.java).setAction(PAUSE_RECEIVE),
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notice =
            android.app.Notification.Builder(this, FOREGROUND_CHANNEL)
                .setSmallIcon(io.github.beacon.app.R.drawable.ic_notification)
                .setContentTitle("Beacon 自动接收")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.app.Notification.Action.Builder(null, "暂停接收", pause).build())
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

    private fun reconcileSchedule() {
        // Independent from the receive queue: cancellation may call back into the service.
        scheduler.execute {
            runCatching { PollingScheduler.apply(this, pollingState) }
                .onFailure {
                    android.util.Log.w("Beacon", "Polling schedule reconciliation failed", it)
                }
        }
    }

    private fun saveState(value: PollingState) {
        val revised = value.copy(revision = pollingState.revision + 1)
        PollingStateStore(this).save(revised)
        val enabledChanged = pollingState.config.enabled != revised.config.enabled
        val schedulingChanged = pollingState.shouldSchedule != revised.shouldSchedule
        pollingState = revised
        reconcileSchedule()

        if (enabledChanged)
            recordEvent(
                if (revised.config.enabled) RuntimeEventKind.RECEIVE_ENABLED
                else RuntimeEventKind.RECEIVE_PAUSED
            )
        if (schedulingChanged && realtimeDelegate.isInitialized()) realtime.pollingChanged()
    }

    private fun sync(
        automatic: Boolean,
        expectedGeneration: Long,
        active: java.util.concurrent.atomic.AtomicBoolean?,
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
        // The attempt timestamp is carried into the result record below instead of being committed
        // on its own first: nothing gates on it (it is diagnostics only), while a second commit
        // meant
        // a second synchronous preference fsync plus a second schedule reconciliation per run.
        val attemptedAtMs = System.currentTimeMillis()
        val settings =
            try {
                TransportSecretStore(this).load()
            } catch (_: Exception) {
                if (automatic)
                    saveState(
                        pollingState.copy(
                            blocked = PollingPolicy.blockingReason(SyncOutcome.CONFIG),
                            lastResult = "连接配置不可用",
                            lastAttemptMs = attemptedAtMs,
                        )
                    )
                recordEvent(eventKind, SyncOutcome.CONFIG)
                return SyncReport(SyncOutcome.CONFIG, "连接配置不可用，请重新保存配置")
            }
        var outcome = SyncOutcome.OK
        val message =
            try {
                HttpMessageSync(settings, store, ::allowed).run()
            } catch (e: Exception) {
                outcome = SyncFailure.outcome(e, e is android.database.sqlite.SQLiteException)
                SyncFailure.describe(e, outcome)
            }
        if (automatic)
            saveState(
                pollingState.copy(
                    blocked = PollingPolicy.blockingReason(outcome),
                    lastAttemptMs = attemptedAtMs,
                    lastSuccessMs =
                        if (outcome == SyncOutcome.OK) System.currentTimeMillis()
                        else pollingState.lastSuccessMs,
                    lastResult = message,
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
            // Maintenance failure must never turn a completed receive/confirmation into a failure.
            runReceiveCleanup(this, store)
        }
        recordEvent(eventKind, outcome)
        return SyncReport(
            outcome,
            listOf(message, notice).filter { it.isNotBlank() }.joinToString("\n"),
        )
    }

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
                            io.github.beacon.app.ipc.RuntimeProtocol.TEST_DELIVERY,
                            io.github.beacon.app.ipc.RuntimeProtocol.HISTORY_ARCHIVE,
                            io.github.beacon.app.ipc.RuntimeProtocol.PENDING_REMINDERS,
                        )
                )
                    return super.onTransact(code, data, reply, flags)
                if (Binder.getCallingUid() != applicationInfo.uid)
                    throw SecurityException("Own UID required")
                data.enforceInterface(DESCRIPTOR)
                if (
                    code == io.github.beacon.app.ipc.RuntimeProtocol.RECONCILE_POLLING ||
                        code == io.github.beacon.app.ipc.RuntimeProtocol.SCHEDULE_TEST
                ) {
                    require(data.dataAvail() == 0)
                    check(controlLoaded.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val task = scheduler.submit {
                        if (code == io.github.beacon.app.ipc.RuntimeProtocol.SCHEDULE_TEST)
                            PollingScheduler.testOnce(this@RuntimeProbeService, pollingState)
                        else PollingScheduler.apply(this@RuntimeProbeService, pollingState)
                    }
                    try {
                        task.get(25, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (error: java.util.concurrent.TimeoutException) {
                        task.cancel(false)
                        throw error
                    }
                    requireNotNull(reply).writeNoException()
                    return true
                }
                if (
                    code == io.github.beacon.app.ipc.RuntimeProtocol.CLEAR_ALL_MESSAGES ||
                        code == io.github.beacon.app.ipc.RuntimeProtocol.PENDING_REMINDERS
                ) {
                    require(data.dataAvail() == 0)
                    val clearing =
                        code == io.github.beacon.app.ipc.RuntimeProtocol.CLEAR_ALL_MESSAGES
                    val result =
                        worker
                            .submit<Long> {
                                if (clearing) {
                                    val count = store.clearAllMessages()
                                    val manager =
                                        getSystemService(
                                            android.app.NotificationManager::class.java
                                        )
                                    manager.activeNotifications
                                        .filter { it.id == MessageNotifications.ID }
                                        .forEach { manager.cancel(it.tag, it.id) }
                                    count
                                } else store.pendingReminderCount()
                            }
                            .get(45, java.util.concurrent.TimeUnit.SECONDS)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeLong(result)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.BARK_ENCRYPTION) {
                    require(data.dataAvail() <= 1024)
                    val key = requireNotNull(data.readString()).also { require(it.length <= 64) }
                    val iv = requireNotNull(data.readString()).also { require(it.length <= 64) }
                    require(data.dataAvail() == 0)
                    val result =
                        worker
                            .submit<String> {
                                TransportSecretStore(this@RuntimeProbeService)
                                    .saveBarkSecret(key, iv)
                                val count = store.decryptHistory(key to iv)
                                "推送解密配置已保存，解密 $count 条历史消息"
                            }
                            .get(30, java.util.concurrent.TimeUnit.SECONDS)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(result)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.TEST_DELIVERY) {
                    require(data.dataAvail() == 0)
                    val result =
                        worker
                            .submit<String> {
                                val device =
                                    TransportSecretStore(this@RuntimeProbeService).loadBound()
                                val id =
                                    io.github.beacon.app.data.remote.HttpDeliveryTest.send(device)
                                sync(false, generation.get(), null)
                                val namespace =
                                    io.github.beacon.app.data.remote.MessageIdentity.namespace(
                                        device.serverId,
                                        device.deviceId,
                                    )
                                val message = store.detail(namespace, id)
                                when {
                                    message == null -> "服务器已接收测试推送，尚未在本机确认保存；请稍后同步"
                                    message.reminderPending -> "测试消息已保存，系统提醒仍待提交；请检查通知权限"
                                    else -> "测试消息已保存，提醒流程已处理；请确认系统通知中是否出现测试消息"
                                }
                            }
                            .get(60, java.util.concurrent.TimeUnit.SECONDS)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(result)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.HISTORY_ARCHIVE) {
                    require(data.dataAvail() <= 20000)
                    val uriText =
                        requireNotNull(data.readString()).also { require(it.length <= 4096) }
                    val password =
                        requireNotNull(data.readString())
                            .also { require(it.length <= 256) }
                            .toCharArray()
                    val importing = data.readInt().also { require(it in 0..1) } == 1
                    val encrypted = data.readInt().also { require(it in 0..1) } == 1
                    require(data.dataAvail() == 0 && (!encrypted || password.size in 8..256))
                    val uri = android.net.Uri.parse(uriText)
                    require(uri.scheme == "content")
                    val task =
                        worker.submit<String> {
                            try {
                                val crypto = io.github.beacon.app.data.local.ArchiveCrypto
                                if (importing) {
                                    val raw =
                                        requireNotNull(contentResolver.openInputStream(uri)).use {
                                            crypto.readBounded(it)
                                        }
                                    val payload =
                                        if (encrypted) crypto.decrypt(raw, password) else raw
                                    try {
                                        "已导入 ${store.importHistory(payload)} 条新历史消息；已有消息保留，不重复通知"
                                    } finally {
                                        payload.fill(0)
                                        raw.fill(0)
                                    }
                                } else {
                                    val payload = store.exportHistory()
                                    val raw =
                                        if (encrypted) crypto.encrypt(payload, password)
                                        else payload
                                    try {
                                        requireNotNull(contentResolver.openOutputStream(uri, "wt"))
                                            .use { it.write(raw) }
                                        "消息历史已导出"
                                    } finally {
                                        raw.fill(0)
                                        payload.fill(0)
                                    }
                                }
                            } finally {
                                password.fill('\u0000')
                            }
                        }
                    val result = task.get(120, java.util.concurrent.TimeUnit.SECONDS)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(result)
                    }
                    return true
                }
                if (
                    code == io.github.beacon.app.ipc.RuntimeProtocol.EXPORT_SETTINGS ||
                        code == io.github.beacon.app.ipc.RuntimeProtocol.IMPORT_SETTINGS
                ) {
                    val importing = code == io.github.beacon.app.ipc.RuntimeProtocol.IMPORT_SETTINGS
                    require(data.dataAvail() <= 17000)
                    val payload = if (importing) requireNotNull(data.readString()) else null
                    require(data.dataAvail() == 0)
                    check(controlLoaded.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val request =
                        worker.submit<String> {
                            val backup =
                                io.github.beacon.app.data.local.SettingsBackup(
                                    this@RuntimeProbeService
                                )
                            if (payload != null) {
                                pollingState = backup.restore(payload, pollingState)
                                reconcileSchedule()

                                realtime.reloadPreferences()
                            }
                            backup.export()
                        }
                    val result =
                        try {
                            request.get(10, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (error: java.util.concurrent.TimeoutException) {
                            request.cancel(false)
                            throw error
                        }
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(result)
                    }
                    return true
                }
                if (
                    code == io.github.beacon.app.ipc.RuntimeProtocol.GET_EVENTS ||
                        code == io.github.beacon.app.ipc.RuntimeProtocol.CLEAR_EVENTS
                ) {
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    val request =
                        worker.submit<List<RuntimeEvent>> {
                            if (code == io.github.beacon.app.ipc.RuntimeProtocol.CLEAR_EVENTS) {
                                events.clear()
                                emptyList()
                            } else events.read()
                        }
                    val history =
                        try {
                            request.get(10, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (error: java.util.concurrent.TimeoutException) {
                            request.cancel(false)
                            throw error
                        }
                    requireNotNull(reply).apply {
                        writeNoException()
                        if (code == io.github.beacon.app.ipc.RuntimeProtocol.GET_EVENTS) {
                            writeInt(history.size)
                            history.forEach {
                                writeLong(it.atMs)
                                writeInt(it.kind.wireId)
                                writeInt(it.outcome?.wireId ?: -1)
                                writeString(it.failure.name)
                            }
                        }
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.RECONNECT_REALTIME) {
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    check(controlLoaded.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val request =
                        worker.submit<RealtimeRetryDecision> {
                            val decision =
                                RealtimeRetryPolicy.decide(
                                    realtime.plan,
                                    pollingState,
                                    TransportSecretStore(this@RuntimeProbeService).isConfigured(),
                                )
                            if (decision == RealtimeRetryDecision.ALLOWED) {
                                if (
                                    pollingState.blocked.isNotBlank() &&
                                        pollingState.lastOutcome == SyncOutcome.TLS
                                )
                                    saveState(pollingState.copy(blocked = ""))
                                recordEvent(RuntimeEventKind.RECONNECT_REQUESTED)
                                realtime.reset()
                                realtime.reconcile()
                            }
                            decision
                        }
                    val decision =
                        try {
                            request.get(45, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (error: java.util.concurrent.TimeoutException) {
                            request.cancel(false)
                            throw error
                        }
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeInt(decision.wireId)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.CLEAR_CONNECTION) {
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    check(controlLoaded.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    generation.incrementAndGet()
                    activeRuns.values.forEach { it.set(false) }
                    val request =
                        worker.submit<String> {
                            saveState(
                                pollingState.copy(
                                    config = pollingState.config.copy(enabled = false),
                                    blocked = "",
                                    lastResult = "服务器配置已清除，自动接收已暂停",
                                )
                            )
                            realtime.reset()
                            realtime.reconcile()
                            TransportSecretStore(this@RuntimeProbeService).clear()
                            recordEvent(RuntimeEventKind.CONNECTION_CLEARED)
                            PollingWire.encode(pollingState)
                        }
                    val result =
                        try {
                            request.get(10, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (error: java.util.concurrent.TimeoutException) {
                            request.cancel(false)
                            throw error
                        }
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(result)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.REMIND_MESSAGE) {
                    val key =
                        MessageKey(
                                requireNotNull(data.readString()),
                                requireNotNull(data.readString()),
                            )
                            .also { it.validate() }
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    val request =
                        worker.submit<String> {
                            remindMessage(this@RuntimeProbeService, store, key)
                        }
                    val result =
                        try {
                            request.get(5, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (error: java.util.concurrent.TimeoutException) {
                            request.cancel(false)
                            throw error
                        }
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(result)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.RECEIVE_PLAN) {
                    val write = data.readInt().also { require(it in 0..1) } == 1
                    val plan =
                        if (write) {
                            val mode = requireNotNull(data.readString())
                            require(
                                io.github.beacon.app.domain.model.ReceiveMode.entries.any {
                                    it.key == mode
                                }
                            )
                            val charging = data.readInt().also { require(it in 0..1) } == 1
                            val wifi = data.readInt().also { require(it in 0..1) } == 1
                            io.github.beacon.app.ipc.ReceivePlanWire.readExtras(
                                data,
                                io.github.beacon.app.domain.model.ReceivePlan(
                                    io.github.beacon.app.domain.model.ReceiveMode.fromKey(mode),
                                    charging,
                                    wifi,
                                ),
                            )
                        } else null
                    require(data.dataAvail() == 0)
                    if (write) generation.incrementAndGet()
                    val snapshot =
                        worker
                            .submit<List<String>> {
                                if (plan != null) realtime.save(plan)
                                val diagnostics = realtime.diagnostics
                                val recovery =
                                    io.github.beacon.app.data.local
                                        .RecoveryStatusStore(this@RuntimeProbeService)
                                        .load()
                                listOf(
                                    realtime.plan.mode.key,
                                    if (realtime.plan.realtimeWhileCharging) "1" else "0",
                                    if (realtime.plan.realtimeOnWifi) "1" else "0",
                                    realtime.status,
                                    if (realtime.running) "1" else "0",
                                    (realtime.deliveryRevision +
                                            io.github.beacon.app.runtime.notification
                                                .NotificationReadReceiver
                                                .revision
                                                .get())
                                        .toString(),
                                    PollingWire.encode(pollingState),
                                    diagnostics.failure.name,
                                    diagnostics.attempts.toString(),
                                    diagnostics.retries.toString(),
                                    diagnostics.connectedAtMs.toString(),
                                    recovery.status.name,
                                    recovery.atMs.toString(),
                                    recovery.attempts.toString(),
                                    if (realtime.plan.scheduleEnabled) "1" else "0",
                                    realtime.plan.scheduleStart.toString(),
                                    realtime.plan.scheduleEnd.toString(),
                                    if (realtime.plan.scheduleRealtime) "1" else "0",
                                    if (realtime.plan.lowBatteryEnabled) "1" else "0",
                                    realtime.plan.lowBatteryPercent.toString(),
                                    realtime.plan.heartbeatSeconds.toString(),
                                )
                            }
                            .get(45, java.util.concurrent.TimeUnit.SECONDS)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(snapshot[0])
                        writeInt(snapshot[1].toInt())
                        writeInt(snapshot[2].toInt())
                        writeString(snapshot[3])
                        writeInt(snapshot[4].toInt())
                        writeLong(snapshot[5].toLong())
                        writeString(snapshot[6])
                        writeString(snapshot[7])
                        writeInt(snapshot[8].toInt())
                        writeInt(snapshot[9].toInt())
                        writeLong(snapshot[10].toLong())
                        writeString(snapshot[11])
                        writeLong(snapshot[12].toLong())
                        writeInt(snapshot[13].toInt())
                        for (index in 14..20) writeInt(snapshot[index].toInt())
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.NOTIFICATION_SETTINGS) {
                    val write = data.readInt().also { require(it in 0..1) } == 1
                    val value = if (write) NotificationSettingsWire.read(data) else null
                    require(data.dataAvail() == 0)
                    val saved =
                        worker
                            .submit<NotificationSettings> {
                                val preferences =
                                    NotificationSettingsStore(this@RuntimeProbeService)
                                if (value != null) preferences.save(value)
                                preferences.load()
                            }
                            .get(45, java.util.concurrent.TimeUnit.SECONDS)
                    requireNotNull(reply).apply {
                        writeNoException()
                        NotificationSettingsWire.write(this, saved)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.QUIET_HOURS) {
                    val write = data.readInt().also { require(it in 0..1) } == 1
                    val value = if (write) QuietHoursWire.read(data) else null
                    require(data.dataAvail() == 0)
                    val saved =
                        worker
                            .submit<io.github.beacon.app.domain.model.QuietHours> {
                                val preferences = QuietHoursStore(this@RuntimeProbeService)
                                if (value != null) preferences.save(value)
                                preferences.load()
                            }
                            .get(45, java.util.concurrent.TimeUnit.SECONDS)
                    requireNotNull(reply).apply {
                        writeNoException()
                        QuietHoursWire.write(this, saved)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.CLEANUP_SETTINGS) {
                    val write = data.readInt().also { require(it in 0..1) } == 1
                    val settings =
                        if (write)
                            io.github.beacon.app.domain.model.CleanupSettings(
                                io.github.beacon.app.domain.model
                                    .CleanupPolicy(
                                        data.readInt(),
                                        data.readInt(),
                                        data.readInt().also { require(it in 0..1) } == 1,
                                    )
                                    .also { it.validate() },
                                data.readInt().also { require(it in 0..1) } == 1,
                            )
                        else null
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    val saved =
                        worker
                            .submit<io.github.beacon.app.domain.model.CleanupSettings> {
                                val preferences =
                                    io.github.beacon.app.data.local.CleanupPreferenceStore(
                                        this@RuntimeProbeService
                                    )
                                if (settings != null) preferences.save(settings)
                                preferences.settings()
                            }
                            .get(45, java.util.concurrent.TimeUnit.SECONDS)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeInt(saved.policy.days)
                        writeInt(saved.policy.maxMessages)
                        writeInt(if (saved.policy.keepUnread) 1 else 0)
                        writeInt(if (saved.automatic) 1 else 0)
                        writeString(saved.lastResult)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.PREVIEW_CLEANUP) {
                    val policy =
                        io.github.beacon.app.domain.model.CleanupPolicy(
                            data.readInt(),
                            data.readInt(),
                            data.readInt().also { require(it in 0..1) } == 1,
                        )
                    policy.validate()
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    val preview =
                        worker
                            .submit<io.github.beacon.app.domain.model.CleanupPreview> {
                                store.previewCleanup(policy)
                            }
                            .get(45, java.util.concurrent.TimeUnit.SECONDS)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(preview.token)
                        writeLong(preview.total)
                        writeLong(preview.unread)
                        writeLong(preview.pending)
                        writeLong(preview.databaseBytes)
                        writeLong(preview.eligible)
                        writeLong(preview.protected)
                        writeInt(preview.planned)
                        writeLong(preview.reusableBytes)
                        writeLong(preview.pendingNotifications)
                        writeLong(preview.tombstones)
                        writeLong(preview.walBytes)
                        writeLong(preview.availableBytes)
                        writeLong(preview.sampledAtMs)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.APPLY_CLEANUP) {
                    val token =
                        requireNotNull(data.readString()).also { require(it.length in 1..64) }
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    val result =
                        worker
                            .submit<MessageMutationResult> { store.applyCleanup(token) }
                            .get(45, java.util.concurrent.TimeUnit.SECONDS)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeInt(result.changed)
                        writeString(result.undo?.token ?: "")
                        writeLong(result.undo?.expiresAtMs ?: 0)
                        writeInt(result.undo?.count ?: 0)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.COPY_PUSH_KEY) {
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    val address =
                        worker
                            .submit<String> {
                                TransportSecretStore(this@RuntimeProbeService).pushKey()
                            }
                            .get(5, java.util.concurrent.TimeUnit.SECONDS)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(address)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.REVOKE_DEVICE) {
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    check(controlLoaded.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + 30_000_000_000L
                    val active = java.util.concurrent.atomic.AtomicBoolean(true)
                    val task =
                        worker.submit<SyncReport> {
                            var confirmed = false
                            try {
                                check(active.get() && System.nanoTime() < deadline)
                                val secrets = TransportSecretStore(this@RuntimeProbeService)
                                val device = secrets.loadBound()
                                io.github.beacon.app.data.remote.HttpDeviceRevocation().revoke(
                                    device,
                                    deadline,
                                ) {
                                    active.get()
                                }
                                confirmed = true
                                // Complete local cleanup after confirmation even if the caller
                                // leaves.
                                generation.incrementAndGet()
                                activeRuns.values.forEach { it.set(false) }
                                saveState(
                                    pollingState.copy(
                                        config = pollingState.config.copy(enabled = false),
                                        blocked = "",
                                        lastResult = "服务端已撤销当前设备，自动接收已暂停",
                                    )
                                )
                                realtime.reset()
                                realtime.reconcile()
                                store.retireRevokedReceipts(
                                    io.github.beacon.app.data.remote.MessageIdentity.namespace(
                                        device.serverId,
                                        device.deviceId,
                                    )
                                )
                                recordEvent(RuntimeEventKind.DEVICE_REVOKED)
                                // Clear last: a partial cleanup can retry the idempotent remote
                                // request.
                                secrets.clear()
                                SyncReport(SyncOutcome.OK, "当前设备已撤销并清除本机配置；历史消息保留，旧推送地址已失效")
                            } catch (error: Exception) {
                                SyncReport(
                                    SyncOutcome.CONFIG,
                                    if (confirmed) "服务端已撤销，本机清理未完成；请重试完整解绑"
                                    else
                                        when (error) {
                                            is io.github.beacon.app.data.remote.DeviceRevocationRejected ->
                                                if (error.status == 404)
                                                    "服务器暂不支持完整解绑，请升级至 0.3.9；本机配置保留"
                                                else "服务端未确认撤销；本机配置保留，请核对设备凭证后重试"
                                            is IllegalArgumentException ->
                                                "当前配置缺少绑定设备身份；请在服务端撤销，或仅清除本机配置"
                                            else -> "撤销结果未确认；本机配置保留，请检查网络后重试"
                                        },
                                )
                            }
                        }
                    val result =
                        try {
                            task.get(35, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (error: Exception) {
                            active.set(false)
                            task.cancel(false)
                            throw error
                        }
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(result.text)
                        writeString(PollingWire.encode(pollingState))
                        writeInt(result.outcome.wireId)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.ROTATE_CREDENTIALS) {
                    val deadline = System.nanoTime() + 30_000_000_000L
                    val payload = requireNotNull(data.readString())
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    val credentials =
                        io.github.beacon.app.data.remote.RotatedCredentialsDecoder.decode(payload)
                    val active = java.util.concurrent.atomic.AtomicBoolean(true)
                    fun allowed() = active.get() && System.nanoTime() < deadline
                    val task =
                        worker.submit<SyncReport> {
                            var saved = false
                            try {
                                check(allowed())
                                TransportSecretStore(this@RuntimeProbeService).rotate(
                                    credentials
                                ) { device ->
                                    HttpMessageSync(device.settings, store, ::allowed)
                                        .verifyDevice(device.serverId, device.deviceId)
                                    check(allowed())
                                }
                                saved = true
                                generation.incrementAndGet()
                                realtime.reset()
                                saveState(pollingState.copy(blocked = "", lastResult = "当前设备凭证已更新"))
                                recordEvent(RuntimeEventKind.CREDENTIALS_ROTATED)
                                realtime.reconcile()
                                SyncReport(SyncOutcome.OK, "新凭证已验证并加密保存；历史与接收开关保留，请更新发送脚本中的推送地址")
                            } catch (error: Exception) {
                                if (saved) SyncReport(SyncOutcome.OK, "新凭证已保存，接收状态更新未完成；请刷新状态或重开应用")
                                else
                                    SyncReport(
                                        SyncOutcome.CONFIG,
                                        when (error) {
                                            is IllegalArgumentException ->
                                                "更新未完成：结果不属于当前绑定设备、格式无效或已使用；原配置保留"
                                            else -> "新凭证验证或保存未完成；原配置保留，请核对网络、证书和最新轮换结果"
                                        },
                                    )
                            }
                        }
                    val result =
                        try {
                            task.get(35, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (error: Exception) {
                            active.set(false)
                            task.cancel(false)
                            throw error
                        }
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(result.text)
                        writeString(PollingWire.encode(pollingState))
                        writeInt(result.outcome.wireId)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.BIND_DEVICE) {
                    val deadline = System.nanoTime() + 30_000_000_000L
                    val request =
                        BindingRequest(
                            requireNotNull(data.readString()),
                            requireNotNull(data.readString()),
                            requireNotNull(data.readString()),
                        )
                    request.validate(BuildConfig.DEBUG)
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    val result =
                        worker
                            .submit<SyncReport> {
                                try {
                                    val device = HttpDeviceBinding().bind(request, deadline)
                                    TransportSecretStore(this@RuntimeProbeService).saveBound(device)
                                    recordEvent(RuntimeEventKind.CONNECTION_SAVED)
                                    generation.incrementAndGet()
                                    saveState(
                                        pollingState.copy(blocked = "", lastResult = "服务器绑定成功")
                                    )
                                    realtime.reset()
                                    realtime.reconcile()
                                    SyncReport(SyncOutcome.OK, "服务器已绑定，凭证已加密保存；可复制推送密钥并立即同步验证")
                                } catch (error: Exception) {
                                    val reason =
                                        when (error) {
                                            is BindingRejected ->
                                                when (error.status) {
                                                    400 -> "绑定码无效、已使用或已过期，请重新生成"
                                                    404 -> "服务器尚不支持绑定码，请先升级服务器"
                                                    429 -> "绑定请求过多，请稍后重试"
                                                    in 300..399 -> "服务器发生重定向，请填写最终 HTTPS 地址"
                                                    else -> "服务器未完成绑定，请稍后重试"
                                                }
                                            is BindingResponseInvalid -> "服务器绑定响应不符合协议，请核对服务器版本"
                                            else -> "绑定未完成；若绑定码已消耗，请在服务器生成新码"
                                        }
                                    SyncReport(SyncOutcome.CONFIG, reason)
                                }
                            }
                            .get(35, java.util.concurrent.TimeUnit.SECONDS)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(result.text)
                        writeString(PollingWire.encode(pollingState))
                        writeInt(result.outcome.wireId)
                    }
                    return true
                }
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.CHECK_SERVER) {
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    val result =
                        serverProbe
                            .submit<Pair<Boolean, String>> {
                                try {
                                    // Snapshot credentials on their owner, then release it before
                                    // network IO so local queries and mutations can proceed.
                                    val sync =
                                        worker
                                            .submit<HttpMessageSync> {
                                                HttpMessageSync(
                                                    TransportSecretStore(this@RuntimeProbeService)
                                                        .load(),
                                                    store,
                                                )
                                            }
                                            .get(5, java.util.concurrent.TimeUnit.SECONDS)
                                    Pair(true, sync.checkServer())
                                } catch (error: Exception) {
                                    Pair(
                                        false,
                                        SyncFailure.describe(error, SyncFailure.outcome(error))
                                            .substringBefore("；已保存"),
                                    )
                                }
                            }
                            .get(35, java.util.concurrent.TimeUnit.SECONDS)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeInt(if (result.first) 1 else 0)
                        writeString(result.second)
                    }
                    return true
                }
                if (code == GET_CONNECTION) {
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    val address =
                        worker
                            .submit<String> {
                                val secret = TransportSecretStore(this@RuntimeProbeService)
                                if (secret.isConfigured()) secret.load().endpoint else ""
                            }
                            .get(5, java.util.concurrent.TimeUnit.SECONDS)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(address)
                    }
                    return true
                }
                if (code == MUTATE || code == UNDO_DELETE) {
                    val requestedAt = System.currentTimeMillis()
                    val request =
                        if (code == MUTATE) {
                            val action =
                                MessageAction.fromWireId(data.readInt())
                                    ?: throw IllegalArgumentException("Invalid action")
                            val count = data.readInt()
                            require(count in 1..100)
                            MessageMutation(
                                    action,
                                    List(count) {
                                        MessageKey(
                                            requireNotNull(data.readString()),
                                            requireNotNull(data.readString()),
                                        )
                                    },
                                )
                                .also { it.validate() }
                        } else null
                    val token =
                        if (code == UNDO_DELETE)
                            requireNotNull(data.readString()).also { require(it.length in 1..64) }
                        else null
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    val result =
                        try {
                            worker
                                .submit<MessageMutationResult> {
                                    if (request != null) store.mutate(request)
                                    else
                                        MessageMutationResult(
                                            0 // Retired transaction: never restore deleted
                                            // messages.
                                        )
                                }
                                .get(45, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            throw IllegalStateException("Message management failed", e)
                        }
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeInt(result.changed)
                        writeString(result.undo?.token ?: "")
                        writeLong(result.undo?.expiresAtMs ?: 0)
                        writeInt(result.undo?.count ?: 0)
                    }
                    return true
                }
                if (code == QUERY || code == DETAIL) {
                    val query =
                        if (code == QUERY) {
                            val search = requireNotNull(data.readString())
                            val serverId = requireNotNull(data.readString())
                            val before = data.readLong()
                            val filter =
                                MessageReadFilter.fromWireId(data.readInt())
                                    ?: throw IllegalArgumentException("Invalid read filter")
                            val anchorServer = if (data.dataAvail() > 0) data.readString() else null
                            val anchorId = if (data.dataAvail() > 0) data.readString() else null
                            require((anchorServer == null) == (anchorId == null))
                            val anchor = anchorServer?.let {
                                MessageKey(it, requireNotNull(anchorId))
                            }
                            MessageQuery(search, serverId, before, filter, anchor).also {
                                it.validate()
                            }
                        } else null
                    val server = if (code == DETAIL) requireNotNull(data.readString()) else null
                    val id = if (code == DETAIL) requireNotNull(data.readString()) else null
                    if (code == DETAIL)
                        require(
                            !server.isNullOrBlank() &&
                                server.length <= 128 &&
                                !id.isNullOrBlank() &&
                                id.length <= 128
                        )
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    val result =
                        try {
                            worker
                                .submit<Any?> {
                                    if (query != null) store.page(query)
                                    else store.detail(requireNotNull(server), requireNotNull(id))
                                }
                                .get(5, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            throw IllegalStateException("History operation failed", e)
                        }
                    requireNotNull(reply).apply {
                        writeNoException()
                        if (query != null) {
                            val page = result as MessagePage
                            writeLong(page.total)
                            writeLong(page.unread)
                            writeLong(page.nextBefore ?: -1)
                            writeInt(page.items.size)
                            page.items.forEach { writeMessage(this, it) }
                            writeString(page.undo?.token ?: "")
                            writeLong(page.undo?.expiresAtMs ?: 0)
                            writeInt(page.undo?.count ?: 0)
                        } else {
                            val message = result as IncomingMessage?
                            writeInt(if (message == null) 0 else 1)
                            if (message != null) writeMessage(this, message)
                        }
                    }
                    return true
                }
                if (code in listOf(GET_CONTROL, SET_POLLING, AUTO_SYNC, STOP_RUN)) {
                    check(controlLoaded.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        "接收设置加载超时"
                    }
                }
                if (code == GET_CONTROL || code == STOP_RUN) {
                    val runId =
                        if (code == STOP_RUN)
                            requireNotNull(data.readString()).also { require(it.length in 1..64) }
                        else null
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    if (runId != null) activeRuns[runId]?.set(false)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(PollingWire.encode(pollingState))
                    }
                    return true
                }
                if (code in listOf(CONFIGURE, SYNC, NOTIFY, SET_POLLING, AUTO_SYNC)) {
                    val settings =
                        if (code == CONFIGURE)
                            TransportSettings(
                                requireNotNull(data.readString()),
                                requireNotNull(data.readString()),
                            )
                        else null
                    settings?.validateForUpdate(BuildConfig.DEBUG)
                    val config =
                        if (code == SET_POLLING)
                            PollingConfig(
                                    data.readInt() == 1,
                                    data.readInt(),
                                    data.readInt() == 1,
                                    data.readInt() == 1,
                                )
                                .also { it.validate() }
                        else null
                    val runId =
                        if (code == AUTO_SYNC)
                            requireNotNull(data.readString()).also { require(it.length in 1..64) }
                        else null
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    if (settings != null || config != null) generation.incrementAndGet()
                    val expectedGeneration = generation.get()
                    val networkOperation = code == SYNC || code == AUTO_SYNC
                    if (networkOperation && !syncing.compareAndSet(false, true)) {
                        requireNotNull(reply).apply {
                            writeNoException()
                            writeString("已有同步正在执行，请稍后读取结果")
                            writeString(PollingWire.encode(pollingState))
                            writeInt(SyncOutcome.BUSY.wireId)
                        }
                        return true
                    }
                    val active =
                        if (runId == null) null
                        else
                            java.util.concurrent.atomic.AtomicBoolean(true).also {
                                activeRuns[runId] = it
                            }
                    val result =
                        try {
                            worker
                                .submit<SyncReport> {
                                    try {
                                        when {
                                            settings != null -> {
                                                val secret =
                                                    TransportSecretStore(this@RuntimeProbeService)
                                                secret.save(
                                                    settings.resolved(BuildConfig.DEBUG) {
                                                        secret.load().token
                                                    }
                                                )
                                                saveState(
                                                    pollingState.copy(
                                                        blocked = "",
                                                        lastResult = "连接配置已更新",
                                                    )
                                                )
                                                realtime.reset()
                                                realtime.reconcile()
                                                recordEvent(RuntimeEventKind.CONNECTION_SAVED)
                                                SyncReport(SyncOutcome.OK, "连接配置已加密保存；请点击立即同步验证")
                                            }
                                            config != null -> {
                                                if (config.enabled)
                                                    TransportSecretStore(this@RuntimeProbeService)
                                                        .load()
                                                saveState(
                                                    pollingState.copy(
                                                        config = config,
                                                        blocked =
                                                            if (config.enabled) ""
                                                            else pollingState.blocked,
                                                    )
                                                )
                                                realtime.reset()
                                                realtime.reconcile()
                                                SyncReport(
                                                    SyncOutcome.OK,
                                                    if (config.enabled) "接收设置已保存，正在应用调度"
                                                    else "自动接收已暂停；保留历史和待确认接收记录，可主动手动同步",
                                                )
                                            }
                                            code == NOTIFY ->
                                                SyncReport(
                                                    SyncOutcome.OK,
                                                    flushNotifications(
                                                        this@RuntimeProbeService,
                                                        store,
                                                    ),
                                                )
                                            else ->
                                                sync(code == AUTO_SYNC, expectedGeneration, active)
                                        }
                                    } finally {
                                        if (networkOperation) syncing.set(false)
                                        if (runId != null) activeRuns.remove(runId)
                                    }
                                }
                                .get(45, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            throw IllegalStateException("Transport operation failed", e)
                        }
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString(result.text)
                        writeString(PollingWire.encode(pollingState))
                        writeInt(result.outcome.wireId)
                    }
                    return true
                }
                if (code != PING) {
                    val server = if (code == DELETE) data.readString() else null
                    val id = if (code == DELETE) data.readString() else null
                    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                    if (code == DELETE)
                        require(
                            !server.isNullOrBlank() &&
                                server.length <= 128 &&
                                !id.isNullOrBlank() &&
                                id.length <= 128
                        )
                    if (code == INJECT_TEST && !BuildConfig.DEBUG)
                        throw SecurityException("Debug only")
                    val rows =
                        try {
                            worker
                                .submit<List<IncomingMessage>> {
                                    if (code == INJECT_TEST) {
                                        // Fixed identity permits repeat-delivery and tombstone
                                        // verification.
                                        ReceivePipeline(store) { _, _ ->
                                                throw java.io.IOException(
                                                    "No server ACK transport configured"
                                                )
                                            }
                                            .accept(
                                                IncomingMessage(
                                                    "diagnostic-local",
                                                    "pipeline-test-${BuildConfig.VERSION_CODE}",
                                                    "本地流水线测试",
                                                    "仅用于验证保存、去重与删除墓碑；不是服务器消息。",
                                                )
                                            )
                                    }
                                    if (code == DELETE)
                                        store.remove(requireNotNull(server), requireNotNull(id))
                                    store.recent()
                                }
                                .get(5, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            throw IllegalStateException("Data operation failed", e)
                        }
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeInt(rows.size)
                        rows.forEach {
                            writeString(it.server)
                            writeString(it.id)
                            writeString(it.title)
                            writeString(it.body)
                        }
                    }
                    return true
                }
                if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
                requireNotNull(reply).apply {
                    writeNoException()
                    writeInt(Process.myPid())
                    writeInt(BuildConfig.VERSION_CODE)
                    writeString(currentProcessName(this@RuntimeProbeService))
                }
                return true
            }
        }

    override fun onBind(intent: Intent): IBinder = endpoint

    private fun writeMessage(reply: Parcel, message: IncomingMessage) {
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
    }

    override fun onDestroy() {
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
