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
            error is IllegalStateException -> "服务状态异常，请重试"
            error is IllegalArgumentException -> "输入有误，请检查后重试"
            error is SecurityException -> "权限不足，请检查应用权限"
            error is java.io.IOException -> "连接中断，请重试"
            error is UnsupportedOperationException -> "当前版本不支持该操作"
            crossProcess -> "接收服务未响应，请重新打开应用"
            else -> "操作未完成，请重试"
        }
}
