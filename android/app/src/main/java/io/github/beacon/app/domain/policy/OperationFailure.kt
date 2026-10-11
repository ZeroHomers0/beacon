package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.receive.SyncCancelledException

/**
 * Stable user-facing reason for an operation that failed before a typed outcome was known.
 *
 * Callers own the surrounding sentence ("消息操作失败（…）；已显示数据保留"); this supplies the reason only. Never
 * return [Throwable.getMessage] or the runtime class name: release builds rename app exceptions, so
 * `javaClass.simpleName` surfaces identifiers such as `i02`, and raw messages can carry endpoints,
 * tokens or remote payloads. Classification is therefore by exception *type*.
 *
 * [crossProcess] is passed by callers that ran the work through Binder:
 * `android.os.RemoteException` is an Android type this package must not import, so it would
 * otherwise fall to the generic text.
 */
object OperationFailure {
    fun describe(error: Exception, crossProcess: Boolean = false): String =
        when {
            error is SyncCancelledException -> "操作已停止"
            error is java.util.concurrent.TimeoutException -> "操作超时，请重试"
            error is java.util.concurrent.ExecutionException -> "后台任务未完成，请重试"
            error is IllegalStateException -> "运行状态或协议数据不符合预期"
            error is IllegalArgumentException -> "参数不符合要求"
            error is SecurityException -> "系统或跨进程权限不足"
            error is java.io.IOException -> "网络或数据传输未完成，请重试"
            error is UnsupportedOperationException -> "当前版本不支持该操作"
            crossProcess -> "调用未收到响应，请重新连接"
            else -> "发生未预期错误，请重试"
        }
}
