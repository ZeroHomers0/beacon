package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.RealtimeConfiguration
import io.github.beacon.app.domain.model.RealtimeDiagnostics
import io.github.beacon.app.domain.model.RealtimeFailure
import io.github.beacon.app.domain.receive.RealtimeReconnectPolicy
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One terminally closeable session. All connection mutations run on one executor. */
class RealtimeClient
internal constructor(
    private val configuration: RealtimeConfiguration,
    private val password: String,
    private val receive: (ByteArray) -> Unit,
    private val onState: (State) -> Unit,
    private val onSubscribed: () -> Unit,
    private val transportFactory: (Int) -> RealtimeTransport,
) : AutoCloseable {
    constructor(
        configuration: RealtimeConfiguration,
        password: String,
        receive: (ByteArray) -> Unit,
        onState: (State) -> Unit,
        onSubscribed: () -> Unit,
        heartbeatSeconds: Int = io.github.beacon.app.domain.model.RealtimeHeartbeat.DEFAULT_SECONDS,
    ) : this(
        configuration,
        password,
        receive,
        onState,
        onSubscribed,
        { failures ->
            WebSocketTransport(
                io.github.beacon.app.domain.model.RealtimeHeartbeat.forFailures(
                    heartbeatSeconds,
                    failures,
                )
            )
        },
    )

    enum class State {
        CONNECTING,
        CONNECTED,
        RETRYING,
        AUTH_FAILED,
        TLS_FAILED,
        CONFIG_FAILED,
        STOPPED,
    }

    private val started = AtomicBoolean()
    private val stopped = AtomicBoolean()
    private val executor =
        ScheduledThreadPoolExecutor(1) { task -> Thread(task, "beacon-realtime-control") }
            .apply {
                setKeepAliveTime(15, TimeUnit.SECONDS)
                allowCoreThreadTimeOut(true)
                removeOnCancelPolicy = true
                setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            }
    private var failures = 0
    private var current: RealtimeTransport? = null
    private var retry: ScheduledFuture<*>? = null
    @Volatile private var attempt = 0L
    private var terminal = false
    private var connectedAtNanos = 0L
    @Volatile
    var diagnostics = RealtimeDiagnostics()
        private set

    /** Nonblocking and idempotent. A closed object must be replaced, never restarted. */
    fun start() {
        check(!stopped.get()) { "Session is closed" }
        if (started.compareAndSet(false, true)) executor.execute(::connect)
    }

    fun stop() = close()

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        executor.execute {
            retry?.cancel(false)
            retire()
            state(State.STOPPED)
        }
        executor.shutdown()
    }

    private fun retire() {
        attempt++
        current?.let { runCatching { it.close() } }
        current = null
    }

    private fun connect() {
        retry = null
        // With no delayed work, the connection actor can release its idle thread.
        executor.allowCoreThreadTimeOut(true)
        if (stopped.get() || terminal) return
        retire()
        diagnostics =
            diagnostics.copy(attempts = (diagnostics.attempts + 1).coerceAtMost(Int.MAX_VALUE - 1))
        val epoch = attempt
        state(State.CONNECTING)
        try {
            configuration.validate()
            val transport = transportFactory(failures)
            current = transport
            transport.connect(
                configuration,
                password,
                object : RealtimeTransport.Listener {
                    override fun lost(error: Throwable) = enqueue { fail(epoch, error) }

                    override fun message(payload: ByteArray) {
                        if (stopped.get() || epoch != attempt)
                            throw java.io.IOException("Obsolete session")
                        receive(payload)
                        if (stopped.get() || epoch != attempt)
                            throw java.io.IOException("Obsolete session")
                    }
                },
            )
            if (stopped.get()) {
                retire()
                return
            }
            transport.ready()
            if (stopped.get()) {
                retire()
                return
            }
            connectedAtNanos = System.nanoTime()
            diagnostics =
                diagnostics.copy(
                    failure = RealtimeFailure.NONE,
                    retries = failures,
                    connectedAtMs = System.currentTimeMillis(),
                )
            state(State.CONNECTED)
            onSubscribed()
        } catch (error: Exception) {
            fail(epoch, error)
        }
    }

    private fun enqueue(action: () -> Unit) {
        if (!stopped.get()) runCatching { executor.execute(action) }
    }

    private fun fail(epoch: Long, error: Throwable) {
        if (stopped.get() || terminal || epoch != attempt) return
        retire()
        val cause = RealtimeFailureClassifier.classify(error)
        diagnostics = diagnostics.copy(failure = cause)
        when {
            cause == RealtimeFailure.AUTH -> {
                terminal = true
                state(State.AUTH_FAILED)
            }
            cause in
                setOf(
                    RealtimeFailure.CERTIFICATE_EXPIRED,
                    RealtimeFailure.CERTIFICATE_NOT_YET_VALID,
                    RealtimeFailure.CERTIFICATE_UNTRUSTED,
                    RealtimeFailure.HOSTNAME,
                ) -> {
                terminal = true
                state(State.TLS_FAILED)
            }
            cause == RealtimeFailure.CONFIG -> {
                terminal = true
                state(State.CONFIG_FAILED)
            }
            else -> {
                // Brief successful handshakes must not restart an unstable network's backoff.
                if (
                    connectedAtNanos != 0L &&
                        System.nanoTime() - connectedAtNanos >= TimeUnit.MINUTES.toNanos(2)
                )
                    failures = 0
                connectedAtNanos = 0L
                failures = (failures + 1).coerceAtMost(100)
                diagnostics = diagnostics.copy(retries = failures)
                state(State.RETRYING)
                // The executor keeps its 15-second idle release even across a distant retry
                // deadline; a parked thread would otherwise outlive the whole backoff.
                retry =
                    executor.schedule(
                        ::connect,
                        maxOf(
                            RealtimeReconnectPolicy.jitteredDelayMs(failures),
                            io.github.beacon.app.domain.receive.RetryAfterPolicy.from(error) ?: 0L,
                        ),
                        TimeUnit.MILLISECONDS,
                    )
            }
        }
    }

    private fun state(value: State) {
        if (!stopped.get() || value == State.STOPPED) runCatching { onState(value) }
    }
}
