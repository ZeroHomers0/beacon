package io.github.beacon.app.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.core.serialExecutor
import io.github.beacon.app.domain.model.OperationStatus
import io.github.beacon.app.domain.policy.OperationFailure

/** Serial service-call executor with generation guards against callbacks from a released binding. */
internal class RuntimeConnection(
    private val serviceClass: Class<*>,
    private val onStatus: (OperationStatus, Boolean) -> Unit,
    private val onConnected: () -> Unit,
) {
    private val worker = serialExecutor("beacon-ui-ipc")
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var binding: ServiceConnection? = null
    @Volatile private var binder: IBinder? = null
    var context: Context? = null
        private set

    val connected: Boolean
        get() = binder != null

    fun start(context: Context) {
        if (binding != null) {
            if (connected) onConnected()
            return
        }
        val app = context.applicationContext
        this.context = app
        onStatus(OperationStatus.connecting("正在连接接收服务"), false)
        val callback =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (binding !== this) return
                    binder = service
                    execute(
                        operation = {
                            it.request(RuntimeProtocol.PING) { reply ->
                                val pid = reply.readInt()
                                val version = reply.readInt()
                                val process = reply.readString()
                                check(
                                    pid == android.os.Process.myPid() &&
                                        version == BuildConfig.VERSION_CODE &&
                                        process == app.packageName
                                )
                                "接收服务已连接：$process · PID $pid · 版本 $version"
                            }
                        },
                        onResult = {
                            onStatus(OperationStatus.diagnostic(it), true)
                            onConnected()
                        },
                        onError = {
                            stop(
                                OperationStatus.connectionLost(
                                    "接收服务验证失败（${OperationFailure.describe(it, crossProcess = true)}）"
                                )
                            )
                        },
                    )
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (binding === this) {
                        // Keep the binding: Android calls onServiceConnected after restarting it.
                        // Unbinding here used to strand a visible UI after a recoverable crash.
                        binder = null
                        onStatus(OperationStatus.connectionLost("接收服务重启中，等待重新连接"), false)
                    }
                }

                override fun onBindingDied(name: ComponentName) {
                    if (binding === this) stop(OperationStatus.connectionLost("接收服务绑定失效，请重新验证"))
                }

                override fun onNullBinding(name: ComponentName) {
                    if (binding === this) stop(OperationStatus.connectionLost("接收服务拒绝绑定"))
                }
            }
        binding = callback
        try {
            if (
                !app.bindService(
                    Intent(app, serviceClass),
                    callback,
                    Context.BIND_AUTO_CREATE,
                )
            ) {
                binding = null
                this.context = null
                onStatus(OperationStatus.connectionLost("接收服务无法绑定"), false)
            }
        } catch (error: RuntimeException) {
            binding = null
            this.context = null
            onStatus(
                OperationStatus.connectionLost("接收服务连接失败（${OperationFailure.describe(error)}）"),
                false,
            )
        }
        main.postDelayed(
            {
                if (binding === callback && binder == null)
                    stop(OperationStatus.connectionLost("接收服务连接超时"))
            },
            10_000,
        )
    }

    fun <T> execute(
        operation: (IBinder) -> T,
        onResult: (T) -> Unit,
        onError: (Exception) -> Unit,
    ): Boolean {
        val service = binder ?: return false
        val expected = binding
        worker.execute {
            try {
                val result = operation(service)
                main.post { if (binding === expected && binder === service) onResult(result) }
            } catch (error: Exception) {
                main.post { if (binding === expected && binder === service) onError(error) }
            }
        }
        return true
    }

    fun stop(status: OperationStatus) {
        main.removeCallbacksAndMessages(null)
        val callback = binding ?: return
        binding = null
        binder = null
        val app = context
        context = null
        try {
            app?.unbindService(callback)
        } catch (_: IllegalArgumentException) {
            /* Already released. */
        }
        onStatus(status, false)
    }

    fun close() {
        stop(OperationStatus.connectionLost("已解除接收服务诊断绑定"))
        worker.shutdown()
    }
}
