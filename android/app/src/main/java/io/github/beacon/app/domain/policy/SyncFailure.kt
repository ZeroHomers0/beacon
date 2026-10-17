package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.SyncOutcome
import io.github.beacon.app.domain.receive.HttpRejectedException
import io.github.beacon.app.domain.receive.HttpTransientException
import io.github.beacon.app.domain.receive.SyncAuthenticationException
import io.github.beacon.app.domain.receive.SyncCancelledException
import io.github.beacon.app.domain.receive.SyncResponseException

/** Only fixed text and typed HTTP metadata may cross into UI/persistent diagnostics. */
object SyncFailure {
    private fun certificateFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(10).any {
            it is java.security.cert.CertificateException ||
                it is java.security.cert.CertPathValidatorException ||
                it is javax.net.ssl.SSLPeerUnverifiedException
        }

    fun outcome(error: Exception, storage: Boolean = false): SyncOutcome =
        when {
            error is SyncCancelledException -> SyncOutcome.STOPPED
            error is SyncAuthenticationException -> SyncOutcome.AUTH
            certificateFailure(error) -> SyncOutcome.TLS
            error is javax.net.ssl.SSLException -> SyncOutcome.TRANSIENT
            error is HttpRejectedException || error is SyncResponseException -> SyncOutcome.PROTOCOL
            storage -> SyncOutcome.STORAGE
            error is java.io.IOException -> SyncOutcome.TRANSIENT
            else -> SyncOutcome.PROTOCOL
        }

    fun describe(error: Exception, outcome: SyncOutcome): String {
        val reason =
            when (error) {
                is SyncAuthenticationException ->
                    "无法验证设备，请重新绑定服务器"
                is HttpRejectedException ->
                    when (error.statusCode) {
                        in 300..399 -> "服务器地址已跳转，请更新地址"
                        404 -> "未找到服务，请检查服务器地址"
                        else -> "服务器拒绝请求，请联系管理员"
                    }
                is HttpTransientException ->
                    if (error.statusCode == 429) "请求太频繁，请稍后重试"
                    else "服务器暂时不可用，请稍后重试"
                is SyncResponseException -> "服务器返回内容异常，请检查地址或更新版本"
                is java.net.UnknownHostException -> "找不到服务器，请检查地址和网络"
                is java.net.SocketTimeoutException -> "连接超时，请检查网络后重试"
                is java.net.ConnectException -> "无法连接服务器，请检查地址和网络"
                is javax.net.ssl.SSLException ->
                    if (outcome == SyncOutcome.TLS) "无法确认连接安全，请检查手机时间或联系管理员"
                    else "连接中断，请稍后重试"
                else ->
                    when (outcome) {
                        SyncOutcome.STOPPED -> "任务已停止"
                        SyncOutcome.STORAGE -> "保存失败，请检查手机剩余空间"
                        SyncOutcome.TLS -> "无法确认连接安全，请检查手机时间或联系管理员"
                        SyncOutcome.TRANSIENT -> "网络暂时不可用，请稍后重试"
                        else -> "服务暂不兼容，请更新应用或联系管理员"
                    }
            }
        return reason
    }
}
